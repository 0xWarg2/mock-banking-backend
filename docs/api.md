# API Documentation

All requests through the gateway require `X-API-Key: mockbank-secret-key` header.

## Account Service (via Gateway :8080)

### List Accounts
```
GET /api/accounts
```

### Get Account
```
GET /api/accounts/{id}
```

### Create Account
```
POST /api/accounts
Content-Type: application/json

{
  "ownerName": "Nguyen Van A",
  "initialBalance": 1000000,
  "currency": "VND"
}
```

### Get Balance
```
GET /api/accounts/{id}/balance
```

### Close Account
```
DELETE /api/accounts/{id}
```

## Transaction Service (via Gateway :8080)

### Transfer
```
POST /api/transactions/transfer
Content-Type: application/json

{
  "fromAccountId": 1,
  "toAccountId": 2,
  "amount": 500000,
  "currency": "VND",
  "description": "Payment for services"
}
```

### Get Transaction
```
GET /api/transactions/{id}
```

### Get Transaction by Reference
```
GET /api/transactions/reference/{referenceId}
```

### Get Transactions by Account
```
GET /api/transactions/account/{accountId}
```

## Internal APIs (NOT accessible through Gateway)

### Debit Account
```
POST /internal/accounts/{id}/debit
Content-Type: application/json
{"amount": 500000}
```

### Credit Account
```
POST /internal/accounts/{id}/credit
Content-Type: application/json
{"amount": 500000}
```
