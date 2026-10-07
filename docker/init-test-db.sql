-- Separate database for integration tests so they can TRUNCATE freely.
CREATE DATABASE aicontent_test OWNER aicontent;
\connect aicontent_test
CREATE EXTENSION IF NOT EXISTS vector;
