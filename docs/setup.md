# Setup Guide

## Prerequisites
- JDK 21
- Docker Desktop
- kubectl (for K8s)
- minikube (for K8s)

## Local Development (run services individually)

### 1. Start infrastructure
```bash
docker compose up -d postgres kafka zookeeper redis
```

### 2. Build all services
```bash
./gradlew build -x test
```

### 3. Run services (each in separate terminal)
```bash
./gradlew :account-service:bootRun
./gradlew :transaction-service:bootRun
./gradlew :notification-service:bootRun
./gradlew :api-gateway:bootRun
```

### 4. Test
```bash
# Direct access (no auth needed)
curl localhost:8081/api/accounts

# Through gateway (requires API key)
curl -H "X-API-Key: mockbank-secret-key" localhost:8080/api/accounts

# Create account
curl -X POST localhost:8080/api/accounts \
  -H "X-API-Key: mockbank-secret-key" \
  -H "Content-Type: application/json" \
  -d '{"ownerName":"Test User","initialBalance":1000000}'

# Transfer
curl -X POST localhost:8080/api/transactions/transfer \
  -H "X-API-Key: mockbank-secret-key" \
  -H "Content-Type: application/json" \
  -d '{"fromAccountId":1,"toAccountId":2,"amount":100000}'
```

## Docker Compose (all services)

```bash
# Start everything
docker compose up --build

# With monitoring
docker compose -f docker-compose.yml -f docker-compose.monitoring.yml up --build
```

- Gateway: http://localhost:8080
- Grafana: http://localhost:3000 (admin/admin)
- Prometheus: http://localhost:9090

## Kubernetes (minikube)

```bash
# Start minikube
minikube start --memory=6144 --cpus=4

# Build images in minikube's Docker
eval $(minikube docker-env)
docker build -f account-service/Dockerfile -t mockbank/account-service:latest .
docker build -f transaction-service/Dockerfile -t mockbank/transaction-service:latest .
docker build -f notification-service/Dockerfile -t mockbank/notification-service:latest .
docker build -f api-gateway/Dockerfile -t mockbank/api-gateway:latest .

# Deploy
kubectl apply -f k8s/namespace.yml
kubectl apply -f k8s/infra/
kubectl apply -f k8s/services/

# Wait for pods
kubectl get pods -n mockbank -w

# Access gateway
minikube tunnel
# Then: curl -H "X-API-Key: mockbank-secret-key" localhost:8080/api/accounts
```
