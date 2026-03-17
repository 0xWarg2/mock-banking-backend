-- Create DC2 databases
CREATE DATABASE dc2_account_db;
CREATE DATABASE dc2_transaction_db;

-- Grant privileges
GRANT ALL PRIVILEGES ON DATABASE dc2_account_db TO mockbank;
GRANT ALL PRIVILEGES ON DATABASE dc2_transaction_db TO mockbank;
