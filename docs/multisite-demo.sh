#!/bin/bash
# Multisite Demo Script - 6 scenarios to verify multi-DC concepts
# Usage: bash docs/multisite-demo.sh
# Prerequisites: docker compose -f docker-compose.yml -f docker-compose.multisite.yml up --build

set -e

API_KEY="mockbank-secret-key"
DC1="http://localhost:8080"
DC2="http://localhost:9080"

echo "=========================================="
echo " Multisite Demo - Mock Banking Backend"
echo "=========================================="
echo ""

# --- Scenario 1: Site ID in logs ---
echo "=== Scenario 1: Site ID in logs ==="
echo "Creating account in DC1..."
curl -s -X POST "$DC1/api/accounts" \
  -H "X-API-Key: $API_KEY" \
  -H "Content-Type: application/json" \
  -d '{"ownerName":"DC1 User Alpha","initialBalance":5000000,"currency":"VND"}' | python3 -m json.tool

echo ""
echo "Creating account in DC2..."
curl -s -X POST "$DC2/api/accounts" \
  -H "X-API-Key: $API_KEY" \
  -H "Content-Type: application/json" \
  -d '{"ownerName":"DC2 User Beta","initialBalance":3000000,"currency":"VND"}' | python3 -m json.tool

echo ""
echo ">> Check logs: docker logs mockbank-account-service 2>&1 | grep '\\[DC1\\]'"
echo ">> Check logs: docker logs mockbank-account-service-dc2 2>&1 | grep '\\[DC2\\]'"
echo ""

# --- Scenario 2: Data isolation ---
echo "=== Scenario 2: Data isolation ==="
echo "Listing accounts on DC1:"
curl -s "$DC1/api/accounts" -H "X-API-Key: $API_KEY" | python3 -m json.tool
echo ""
echo "Listing accounts on DC2:"
curl -s "$DC2/api/accounts" -H "X-API-Key: $API_KEY" | python3 -m json.tool
echo ""
echo ">> DC1 should only see DC1 accounts, DC2 should only see DC2 accounts"
echo ""

# --- Scenario 3: Kafka isolation ---
echo "=== Scenario 3: Kafka isolation ==="
echo "Creating second account on DC1 for transfer..."
curl -s -X POST "$DC1/api/accounts" \
  -H "X-API-Key: $API_KEY" \
  -H "Content-Type: application/json" \
  -d '{"ownerName":"DC1 User Bravo","initialBalance":2000000,"currency":"VND"}' | python3 -m json.tool

echo ""
echo "Transferring 500K on DC1 (account 1 -> 2)..."
curl -s -X POST "$DC1/api/transactions/transfer" \
  -H "X-API-Key: $API_KEY" \
  -H "Content-Type: application/json" \
  -d '{"fromAccountId":1,"toAccountId":2,"amount":500000,"description":"DC1 transfer test"}' | python3 -m json.tool

echo ""
echo ">> Check DC1 notification: docker logs mockbank-notification-service 2>&1 | grep 'NOTIFICATION'"
echo ">> Check DC2 notification: docker logs mockbank-notification-service-dc2 2>&1 | grep 'NOTIFICATION'"
echo ">> DC2 notification should NOT have DC1's event"
echo ""

# --- Scenario 4: Redis cache isolation ---
echo "=== Scenario 4: Redis cache isolation ==="
echo "Querying balance on DC1..."
curl -s "$DC1/api/accounts/1/balance" -H "X-API-Key: $API_KEY"
echo ""
echo "Querying balance on DC2..."
curl -s "$DC2/api/accounts/1/balance" -H "X-API-Key: $API_KEY"
echo ""
echo ""
echo ">> Check Redis keys DC1: docker exec mockbank-redis redis-cli KEYS '*'"
echo ">> Check Redis keys DC2: docker exec mockbank-redis-dc2 redis-cli KEYS '*'"
echo ">> DC1 keys should be prefixed with 'DC1:', DC2 with 'DC2:'"
echo ""

# --- Scenario 5: Job deduplication ---
echo "=== Scenario 5: Job deduplication ==="
echo ">> Wait ~60 seconds for reconciliation job to run, then check:"
echo ">> docker logs mockbank-account-service 2>&1 | grep 'Reconciliation'"
echo ">> docker logs mockbank-account-service-dc2 2>&1 | grep 'Reconciliation'"
echo ">> Each DC should run its own job with different Redis lock keys"
echo ">> Check lock keys: docker exec mockbank-redis redis-cli KEYS 'job:*'"
echo ">> Check lock keys: docker exec mockbank-redis-dc2 redis-cli KEYS 'job:*'"
echo ""

# --- Scenario 6: Cross-site routing blocked ---
echo "=== Scenario 6: Cross-site routing blocked ==="
echo "DC2 accounts (should NOT contain DC1 accounts):"
curl -s "$DC2/api/accounts" -H "X-API-Key: $API_KEY" | python3 -m json.tool
echo ""
echo "DC1 accounts (should NOT contain DC2 accounts):"
curl -s "$DC1/api/accounts" -H "X-API-Key: $API_KEY" | python3 -m json.tool
echo ""
echo ">> Each gateway routes to its own set of services - complete isolation"
echo ""

echo "=========================================="
echo " Demo Complete!"
echo "=========================================="
