/*
 * ============================================================================
 *  LAST-MILE DELIVERY TRACKING API  (single-file Java project)
 * ============================================================================
 *  Stack          : Pure JDK only (no external jars needed)
 *                   - com.sun.net.httpserver.HttpServer  -> REST layer
 *                   - java.security.Mac (HmacSHA256)     -> JWT-style tokens
 *                   - In-memory data store (ConcurrentHashMap)
 *
 *  Features
 *  --------
 *  1. Role based auth (MERCHANT / AGENT / CUSTOMER) with JWT-like signed tokens
 *  2. Merchant creates shipments, system auto-assigns the LEAST BUSY agent
 *  3. Agent updates shipment status -> triggers an async email notification
 *  4. Customer / anyone tracks a package via a public tracking token
 *  5. Async "email service" backed by a BlockingQueue + worker thread
 *
 *  DSA concepts deliberately built into the business logic (not bolted on)
 *  ------------------------------------------------------------------------
 *  (a) GRAPH + DIJKSTRA'S ALGORITHM
 *        Delivery hubs form a weighted graph. When a shipment is created we
 *        compute the shortest-time route between origin & destination hub
 *        to produce an ETA. See HubGraph.shortestPath().
 *
 *  (b) PRIORITY QUEUE (min-heap)
 *        Agents are ranked by current active-shipment count. Assignment
 *        always pops the least loaded agent -> classic greedy load
 *        balancing. See AgentAssigner.pickLeastBusyAgent().
 *
 *  (c) STACK (Deque used as LIFO)
 *        Every shipment keeps a status-history stack so the latest status
 *        change can be inspected/undone (peek/pop semantics) -> see
 *        Shipment.statusHistory.
 *
 *  (d) BLOCKING QUEUE (producer/consumer)
 *        Email notifications are produced by request handlers and consumed
 *        by a background worker thread -> EmailService.
 *
 *  How to run
 *  ----------
 *      javac DeliveryTrackingApp.java
 *      java DeliveryTrackingApp
 *
 *  Server starts on http://localhost:8080
 *
 *  Quick test flow
 *  ----------------
 *      1) POST /api/auth/register  {"username":"m1","password":"pass","email":"m1@x.com","role":"MERCHANT"}
 *      2) POST /api/auth/register  {"username":"a1","password":"pass","email":"a1@x.com","role":"AGENT"}
 *      3) POST /api/auth/login     {"username":"m1","password":"pass"}   -> copy token
 *      4) POST /api/shipments      (Authorization: Bearer <token>)
 *                                  {"originHub":"A","destHub":"D","customerEmail":"cust@x.com"}
 *      5) PUT  /api/shipments/status  (token from agent login)
 *                                  {"shipmentId":"...","status":"PICKED_UP"}
 *      6) GET  /api/track?token=<trackingToken>
 * ============================================================================
 */

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DeliveryTrackingApp {

    public static void main(String[] args) throws IOException {
        Database db = new Database();
        EmailService emailService = new EmailService();
        emailService.start();

        HubGraph hubGraph = HubGraph.buildDefault();
        AgentAssigner assigner = new AgentAssigner(db);
        JwtUtil jwt = new JwtUtil("super-secret-key-change-me");

        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);

        server.createContext("/api/auth/register", new RegisterHandler(db));
        server.createContext("/api/auth/login", new LoginHandler(db, jwt));
        server.createContext("/api/shipments/status", new UpdateStatusHandler(db, jwt, emailService));
        server.createContext("/api/shipments", new ShipmentHandler(db, jwt, hubGraph, assigner, emailService));
        server.createContext("/api/track", new TrackHandler(db));
        server.createContext("/api/agents/load", new AgentLoadHandler(db, jwt));

        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();
        System.out.println("Delivery Tracking API running on http://localhost:8080");
    }

    // ========================================================================
    //  DOMAIN MODEL
    // ========================================================================

    enum Role { MERCHANT, AGENT, CUSTOMER }

    enum Status {
        CREATED, ASSIGNED, PICKED_UP, IN_TRANSIT, DELIVERED;

        static boolean isValid(String s) {
            for (Status st : values()) if (st.name().equalsIgnoreCase(s)) return true;
            return false;
        }
    }

    static class User {
        String username;
        String password; // demo only - plaintext. Use BCrypt in production.
        String email;
        Role role;
    }

    static class Shipment {
        String id = UUID.randomUUID().toString();
        String trackingToken = UUID.randomUUID().toString().replace("-", "");
        String merchantUsername;
        String agentUsername;
        String customerEmail;
        String originHub;
        String destHub;
        double etaHours;
        Status status = Status.CREATED;
        // (c) STACK: most recent status change is on top -> LIFO history
        Deque<String> statusHistory = new ArrayDeque<>();
        long createdAt = System.currentTimeMillis();

        Shipment() {
            statusHistory.push(Status.CREATED.name() + "@" + Instant.now());
        }

        void pushStatus(Status s) {
            this.status = s;
            statusHistory.push(s.name() + "@" + Instant.now());
        }
    }

    // ========================================================================
    //  IN-MEMORY DATABASE
    // ========================================================================

    static class Database {
        final Map<String, User> users = new ConcurrentHashMap<>();         // username -> User
        final Map<String, Shipment> shipments = new ConcurrentHashMap<>(); // id -> Shipment
        final Map<String, String> trackingIndex = new ConcurrentHashMap<>();// trackingToken -> shipmentId
        // active shipment count per agent, used by the priority queue assigner
        final Map<String, Integer> agentLoad = new ConcurrentHashMap<>();
    }

    // ========================================================================
    //  (a) GRAPH + DIJKSTRA -> ETA calculation between hubs
    // ========================================================================

    static class HubGraph {
        final Map<String, List<double[]>> adj = new HashMap<>(); // node -> list of [neighborIndex(asCharCode), weight]
        final Map<String, Map<String, Double>> edges = new HashMap<>();

        static HubGraph buildDefault() {
            HubGraph g = new HubGraph();
            g.addEdge("A", "B", 2.0);
            g.addEdge("A", "C", 5.0);
            g.addEdge("B", "C", 1.0);
            g.addEdge("B", "D", 4.0);
            g.addEdge("C", "D", 1.5);
            g.addEdge("C", "E", 3.0);
            g.addEdge("D", "E", 1.0);
            return g;
        }

        void addEdge(String u, String v, double w) {
            edges.computeIfAbsent(u, k -> new HashMap<>()).put(v, w);
            edges.computeIfAbsent(v, k -> new HashMap<>()).put(u, w);
        }

        /** Classic Dijkstra shortest path using a min-heap (PriorityQueue). */
        double shortestPath(String start, String end) {
            if (!edges.containsKey(start) || !edges.containsKey(end)) return 3.0; // fallback ETA
            Map<String, Double> dist = new HashMap<>();
            for (String node : edges.keySet()) dist.put(node, Double.MAX_VALUE);
            dist.put(start, 0.0);

            PriorityQueue<double[]> pq = new PriorityQueue<>(Comparator.comparingDouble(a -> a[1]));
            Map<Integer, String> idLookup = new HashMap<>();
            // We store node names via a side map since PQ needs numeric comparisons.
            Map<String, Integer> nodeId = new HashMap<>();
            int counter = 0;
            for (String node : edges.keySet()) { nodeId.put(node, counter); idLookup.put(counter, node); counter++; }

            pq.add(new double[]{nodeId.get(start), 0.0});
            Set<String> visited = new HashSet<>();

            while (!pq.isEmpty()) {
                double[] cur = pq.poll();
                String u = idLookup.get((int) cur[0]);
                if (visited.contains(u)) continue;
                visited.add(u);
                if (u.equals(end)) break;

                for (Map.Entry<String, Double> nb : edges.getOrDefault(u, Collections.emptyMap()).entrySet()) {
                    String v = nb.getKey();
                    double weight = nb.getValue();
                    double newDist = dist.get(u) + weight;
                    if (newDist < dist.getOrDefault(v, Double.MAX_VALUE)) {
                        dist.put(v, newDist);
                        pq.add(new double[]{nodeId.get(v), newDist});
                    }
                }
            }
            Double result = dist.get(end);
            return (result == null || result == Double.MAX_VALUE) ? 3.0 : result;
        }
    }

    // ========================================================================
    //  (b) PRIORITY QUEUE -> pick least busy agent (greedy load balancing)
    // ========================================================================

    static class AgentAssigner {
        final Database db;
        AgentAssigner(Database db) { this.db = db; }

        /** Returns the username of the agent with the fewest active shipments. */
        String pickLeastBusyAgent() {
            List<User> agents = new ArrayList<>();
            for (User u : db.users.values()) if (u.role == Role.AGENT) agents.add(u);
            if (agents.isEmpty()) return null;

            // Min-heap ordered by current load.
            PriorityQueue<User> pq = new PriorityQueue<>(
                    Comparator.comparingInt(a -> db.agentLoad.getOrDefault(a.username, 0)));
            pq.addAll(agents);
            User chosen = pq.poll();
            db.agentLoad.merge(chosen.username, 1, Integer::sum);
            return chosen.username;
        }
    }

    // ========================================================================
    //  (d) BLOCKING QUEUE -> async email worker (producer / consumer)
    // ========================================================================

    static class EmailTask {
        String to, subject, body;
        EmailTask(String to, String subject, String body) { this.to = to; this.subject = subject; this.body = body; }
    }

    static class EmailService {
        private final BlockingQueue<EmailTask> queue = new LinkedBlockingQueue<>();
        private final List<String> sentLog = new CopyOnWriteArrayList<>();

        void start() {
            Thread worker = new Thread(() -> {
                while (true) {
                    try {
                        EmailTask task = queue.take(); // blocks until an email is queued
                        // --- Simulated send. Replace with real SMTP / JavaMail in production. ---
                        String line = "[EMAIL -> " + task.to + "] " + task.subject + " :: " + task.body;
                        System.out.println(line);
                        sentLog.add(line);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }, "email-worker");
            worker.setDaemon(true);
            worker.start();
        }

        void enqueue(String to, String subject, String body) {
            queue.offer(new EmailTask(to, subject, body));
        }
    }

    // ========================================================================
    //  JWT-LIKE TOKEN UTILITY (HMAC-SHA256 signed, base64url encoded)
    // ========================================================================

    static class JwtUtil {
        private final String secret;
        JwtUtil(String secret) { this.secret = secret; }

        String issue(String username, Role role, long ttlMillis) {
            long exp = System.currentTimeMillis() + ttlMillis;
            String payload = username + "|" + role.name() + "|" + exp;
            String encodedPayload = b64(payload);
            String sig = sign(encodedPayload);
            return encodedPayload + "." + sig;
        }

        /** Returns [username, role] if valid & not expired, else null. */
        String[] verify(String token) {
            try {
                int dot = token.lastIndexOf('.');
                if (dot < 0) return null;
                String encodedPayload = token.substring(0, dot);
                String sig = token.substring(dot + 1);
                if (!sign(encodedPayload).equals(sig)) return null;
                String payload = new String(Base64.getUrlDecoder().decode(encodedPayload), StandardCharsets.UTF_8);
                String[] parts = payload.split("\\|");
                long exp = Long.parseLong(parts[2]);
                if (System.currentTimeMillis() > exp) return null;
                return new String[]{parts[0], parts[1]};
            } catch (Exception e) {
                return null;
            }
        }

        private String b64(String s) {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
        }

        private String sign(String data) {
            try {
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
                byte[] out = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
                return Base64.getUrlEncoder().withoutPadding().encodeToString(out);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    // ========================================================================
    //  TINY JSON HELPERS (no external libs - good enough for flat objects)
    // ========================================================================

    static class Json {
        static Map<String, String> parse(String body) {
            Map<String, String> map = new LinkedHashMap<>();
            if (body == null) return map;
            Pattern p = Pattern.compile("\"(.*?)\"\\s*:\\s*(\"(.*?)\"|[-0-9.]+|true|false|null)");
            Matcher m = p.matcher(body);
            while (m.find()) {
                String key = m.group(1);
                String val = m.group(3) != null ? m.group(3) : m.group(2);
                map.put(key, val);
            }
            return map;
        }

        static String esc(String s) {
            return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
        }

        static String obj(Object... kv) {
            StringBuilder sb = new StringBuilder("{");
            for (int i = 0; i < kv.length; i += 2) {
                if (i > 0) sb.append(",");
                sb.append("\"").append(kv[i]).append("\":");
                Object v = kv[i + 1];
                if (v instanceof Number || v instanceof Boolean) sb.append(v);
                else if (v instanceof List) sb.append(v); // already a json array string
                else sb.append("\"").append(esc(String.valueOf(v))).append("\"");
            }
            sb.append("}");
            return sb.toString();
        }

        static String arr(Collection<String> items) {
            StringBuilder sb = new StringBuilder("[");
            int i = 0;
            for (String it : items) {
                if (i++ > 0) sb.append(",");
                sb.append("\"").append(esc(it)).append("\"");
            }
            sb.append("]");
            return sb.toString();
        }
    }

    // ========================================================================
    //  HTTP HELPERS
    // ========================================================================

    static String readBody(HttpExchange ex) throws IOException {
        return new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    static void send(HttpExchange ex, int code, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    static Map<String, String> queryParams(HttpExchange ex) {
        Map<String, String> map = new HashMap<>();
        String q = ex.getRequestURI().getQuery();
        if (q == null) return map;
        for (String pair : q.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2) map.put(kv[0], kv[1]);
        }
        return map;
    }

    /** Returns [username, role] from Authorization header, or null if missing/invalid. */
    static String[] authenticate(HttpExchange ex, JwtUtil jwt) {
        String header = ex.getRequestHeaders().getFirst("Authorization");
        if (header == null || !header.startsWith("Bearer ")) return null;
        return jwt.verify(header.substring(7).trim());
    }

    // ========================================================================
    //  HANDLERS
    // ========================================================================

    static class RegisterHandler implements HttpHandler {
        final Database db;
        RegisterHandler(Database db) { this.db = db; }

        public void handle(HttpExchange ex) throws IOException {
            if (!"POST".equals(ex.getRequestMethod())) { send(ex, 405, Json.obj("error", "method not allowed")); return; }
            Map<String, String> body = Json.parse(readBody(ex));
            String username = body.get("username"), password = body.get("password"), email = body.get("email"), roleStr = body.get("role");
            if (username == null || password == null || roleStr == null) { send(ex, 400, Json.obj("error", "username, password, role required")); return; }
            if (db.users.containsKey(username)) { send(ex, 409, Json.obj("error", "username already exists")); return; }
            Role role;
            try { role = Role.valueOf(roleStr.toUpperCase()); }
            catch (Exception e) { send(ex, 400, Json.obj("error", "role must be MERCHANT, AGENT or CUSTOMER")); return; }

            User u = new User();
            u.username = username; u.password = password; u.email = email; u.role = role;
            db.users.put(username, u);
            if (role == Role.AGENT) db.agentLoad.put(username, 0);
            send(ex, 201, Json.obj("message", "registered", "username", username, "role", role.name()));
        }
    }

    static class LoginHandler implements HttpHandler {
        final Database db; final JwtUtil jwt;
        LoginHandler(Database db, JwtUtil jwt) { this.db = db; this.jwt = jwt; }

        public void handle(HttpExchange ex) throws IOException {
            if (!"POST".equals(ex.getRequestMethod())) { send(ex, 405, Json.obj("error", "method not allowed")); return; }
            Map<String, String> body = Json.parse(readBody(ex));
            String username = body.get("username"), password = body.get("password");
            User u = db.users.get(username);
            if (u == null || !u.password.equals(password)) { send(ex, 401, Json.obj("error", "invalid credentials")); return; }
            String token = jwt.issue(u.username, u.role, 2 * 60 * 60 * 1000L); // 2 hours
            send(ex, 200, Json.obj("token", token, "role", u.role.name(), "username", u.username));
        }
    }

    static class ShipmentHandler implements HttpHandler {
        final Database db; final JwtUtil jwt; final HubGraph graph; final AgentAssigner assigner; final EmailService email;
        ShipmentHandler(Database db, JwtUtil jwt, HubGraph graph, AgentAssigner assigner, EmailService email) {
            this.db = db; this.jwt = jwt; this.graph = graph; this.assigner = assigner; this.email = email;
        }

        public void handle(HttpExchange ex) throws IOException {
            String[] auth = authenticate(ex, jwt);
            if (auth == null) { send(ex, 401, Json.obj("error", "unauthorized - missing/invalid token")); return; }
            String username = auth[0], role = auth[1];

            if ("POST".equals(ex.getRequestMethod())) {
                if (!role.equals(Role.MERCHANT.name())) { send(ex, 403, Json.obj("error", "only MERCHANT can create shipments")); return; }
                Map<String, String> body = Json.parse(readBody(ex));
                String originHub = body.getOrDefault("originHub", "A");
                String destHub = body.getOrDefault("destHub", "D");
                String customerEmail = body.get("customerEmail");

                Shipment s = new Shipment();
                s.merchantUsername = username;
                s.originHub = originHub;
                s.destHub = destHub;
                s.customerEmail = customerEmail;

                // (a) Dijkstra ETA
                s.etaHours = graph.shortestPath(originHub, destHub);

                // (b) Priority-queue based least-busy agent assignment
                String agent = assigner.pickLeastBusyAgent();
                s.agentUsername = agent;
                if (agent != null) s.pushStatus(Status.ASSIGNED);

                db.shipments.put(s.id, s);
                db.trackingIndex.put(s.trackingToken, s.id);

                if (customerEmail != null) {
                    email.enqueue(customerEmail, "Shipment Created",
                            "Your shipment " + s.id + " was created. ETA ~" + s.etaHours + "h. Track with token: " + s.trackingToken);
                }

                send(ex, 201, Json.obj(
                        "id", s.id, "trackingToken", s.trackingToken, "status", s.status.name(),
                        "agentAssigned", agent == null ? "none" : agent, "etaHours", s.etaHours));
                return;
            }

            if ("GET".equals(ex.getRequestMethod())) {
                List<String> results = new ArrayList<>();
                for (Shipment s : db.shipments.values()) {
                    boolean visible = (role.equals(Role.MERCHANT.name()) && username.equals(s.merchantUsername))
                            || (role.equals(Role.AGENT.name()) && username.equals(s.agentUsername));
                    if (visible) {
                        results.add(Json.obj("id", s.id, "status", s.status.name(), "trackingToken", s.trackingToken,
                                "originHub", s.originHub, "destHub", s.destHub, "etaHours", s.etaHours));
                    }
                }
                send(ex, 200, "[" + String.join(",", results) + "]");
                return;
            }

            send(ex, 405, Json.obj("error", "method not allowed"));
        }
    }

    static class UpdateStatusHandler implements HttpHandler {
        final Database db; final JwtUtil jwt; final EmailService email;
        UpdateStatusHandler(Database db, JwtUtil jwt, EmailService email) { this.db = db; this.jwt = jwt; this.email = email; }

        public void handle(HttpExchange ex) throws IOException {
            if (!"PUT".equals(ex.getRequestMethod())) { send(ex, 405, Json.obj("error", "method not allowed")); return; }
            String[] auth = authenticate(ex, jwt);
            if (auth == null) { send(ex, 401, Json.obj("error", "unauthorized")); return; }
            String username = auth[0], role = auth[1];
            if (!role.equals(Role.AGENT.name())) { send(ex, 403, Json.obj("error", "only AGENT can update status")); return; }

            Map<String, String> body = Json.parse(readBody(ex));
            String shipmentId = body.get("shipmentId");
            String newStatus = body.get("status");
            Shipment s = db.shipments.get(shipmentId);
            if (s == null) { send(ex, 404, Json.obj("error", "shipment not found")); return; }
            if (!username.equals(s.agentUsername)) { send(ex, 403, Json.obj("error", "not your shipment")); return; }
            if (newStatus == null || !Status.isValid(newStatus)) { send(ex, 400, Json.obj("error", "invalid status")); return; }

            Status statusEnum = Status.valueOf(newStatus.toUpperCase());
            s.pushStatus(statusEnum);

            if (statusEnum == Status.DELIVERED) {
                db.agentLoad.merge(username, -1, Integer::sum); // free up agent capacity
            }

            if (s.customerEmail != null) {
                email.enqueue(s.customerEmail, "Shipment Update: " + statusEnum.name(),
                        "Your shipment " + s.id + " is now " + statusEnum.name() + ".");
            }

            send(ex, 200, Json.obj("id", s.id, "status", s.status.name(), "historyTop", s.statusHistory.peek()));
        }
    }

    static class TrackHandler implements HttpHandler {
        final Database db;
        TrackHandler(Database db) { this.db = db; }

        public void handle(HttpExchange ex) throws IOException {
            if (!"GET".equals(ex.getRequestMethod())) { send(ex, 405, Json.obj("error", "method not allowed")); return; }
            String token = queryParams(ex).get("token");
            if (token == null) { send(ex, 400, Json.obj("error", "token query param required")); return; }
            String shipmentId = db.trackingIndex.get(token);
            if (shipmentId == null) { send(ex, 404, Json.obj("error", "invalid tracking token")); return; }
            Shipment s = db.shipments.get(shipmentId);

            List<String> history = new ArrayList<>(s.statusHistory); // top (most recent) first
            send(ex, 200, Json.obj(
                    "id", s.id, "status", s.status.name(), "originHub", s.originHub, "destHub", s.destHub,
                    "etaHours", s.etaHours, "history", Json.arr(history)));
        }
    }

    static class AgentLoadHandler implements HttpHandler {
        final Database db; final JwtUtil jwt;
        AgentLoadHandler(Database db, JwtUtil jwt) { this.db = db; this.jwt = jwt; }

        public void handle(HttpExchange ex) throws IOException {
            String[] auth = authenticate(ex, jwt);
            if (auth == null) { send(ex, 401, Json.obj("error", "unauthorized")); return; }
            List<String> rows = new ArrayList<>();
            for (Map.Entry<String, Integer> e : db.agentLoad.entrySet()) {
                rows.add(Json.obj("agent", e.getKey(), "activeShipments", e.getValue()));
            }
            send(ex, 200, "[" + String.join(",", rows) + "]");
        }
    }
}