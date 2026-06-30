# 📦 Delivery Tracking API

A RESTful Delivery Tracking API developed using **Core Java** and the built-in **HttpServer**. This project simulates a real-world delivery tracking system with user authentication, shipment management, agent assignment, shipment tracking, and load balancing.

---

## 🚀 Features

- User Registration (Merchant & Agent)
- User Login with Token Authentication
- Create Shipment
- Automatic Agent Assignment
- Update Shipment Status
- Track Shipment using Tracking Token
- View Agent Load
- REST API using Java HttpServer
- JSON Request & Response Handling
- Role-Based Authorization

---

## 🛠️ Technologies Used

- Java (JDK 26)
- Java HttpServer
- Core Java
- Collections Framework
- UUID
- HashMap
- VS Code
- Thunder Client (API Testing)

---

## 📁 Project Structure

```
DeliveryTrackingAPI/
│
├── DeliveryTrackingApp.java
└── README.md
```

---

## ⚙️ How to Run

### 1. Clone the Repository

```bash
git clone https://github.com/YOUR_USERNAME/DeliveryTrackingAPI.git
```

### 2. Open the Project

Open the project folder in **Visual Studio Code**.

### 3. Compile

```bash
javac DeliveryTrackingApp.java
```

### 4. Run

```bash
java DeliveryTrackingApp
```

You should see:

```
Delivery Tracking API running on http://localhost:8080
```

---

# 📡 API Endpoints

## 1. Register User

**POST**

```
http://localhost:8080/api/auth/register
```

### Request

```json
{
  "username":"merchant1",
  "password":"123",
  "email":"merchant@gmail.com",
  "role":"MERCHANT"
}
```

---

## 2. Login User

**POST**

```
http://localhost:8080/api/auth/login
```

### Request

```json
{
  "username":"merchant1",
  "password":"123"
}
```

---

## 3. Create Shipment

**POST**

```
http://localhost:8080/api/shipments
```

### Header

```
Authorization: Bearer <Merchant Token>
```

---

## 4. Update Shipment Status

**PUT**

```
http://localhost:8080/api/shipments/status
```

### Header

```
Authorization: Bearer <Agent Token>
```

### Request

```json
{
  "shipmentId":"Shipment_ID",
  "status":"PICKED_UP"
}
```

---

## 5. Track Shipment

**GET**

```
http://localhost:8080/api/track?token=Tracking_Token
```

---

## 6. Agent Load

**GET**

```
http://localhost:8080/api/agents/load
```

### Header

```
Authorization: Bearer <Agent Token>
```

---

# 🔄 Project Workflow

```
Start Server
      │
      ▼
Register Agent
      │
      ▼
Login Agent
      │
      ▼
Register Merchant
      │
      ▼
Login Merchant
      │
      ▼
Create Shipment
      │
      ▼
Update Shipment Status
      │
      ▼
Track Shipment
      │
      ▼
View Agent Load
```

---

# 📌 Sample Response

### Create Shipment

```json
{
  "id":"55b4b270-c9b6-4e8a-910d-06c544a98555",
  "trackingToken":"043ddfffbce44ecaa14c701981faa635",
  "status":"CREATED",
  "agentAssigned":"agent1",
  "etaHours":4.5
}
```

---

### Update Shipment Status

```json
{
  "id":"55b4b270-c9b6-4e8a-910d-06c544a98555",
  "status":"PICKED_UP",
  "historyTop":"PICKED_UP@2026-06-30T14:55:37Z"
}
```

---

# 📋 Project Highlights

- REST API Development
- Token-Based Authentication
- Role-Based Access Control
- Shipment Tracking
- Automatic Agent Assignment
- Agent Load Monitoring
- JSON Processing
- In-Memory Data Storage

---

# 🔮 Future Enhancements

- MySQL Database Integration
- Spring Boot Migration
- JWT Authentication
- Password Encryption
- Swagger API Documentation
- Docker Support
- Email Notifications
- Delivery Analytics Dashboard

---

# 👨‍💻 Author

**Rohith S**

B.Tech – Information Technology

---

# 📄 License

This project is developed for educational and learning purposes.
