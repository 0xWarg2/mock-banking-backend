-- Create databases for each microservice
CREATE DATABASE transaction_db;
CREATE DATABASE notification_db;

-- Grant privileges
GRANT ALL PRIVILEGES ON DATABASE account_db TO mockbank;
GRANT ALL PRIVILEGES ON DATABASE transaction_db TO mockbank;
GRANT ALL PRIVILEGES ON DATABASE notification_db TO mockbank;
