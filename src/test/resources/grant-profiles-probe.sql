-- Fixture for GrantProfilesProbeTest (sqlx-84o): a schema with the shapes the
-- schema loader reads — a foreign key, indexes, table and column comments, a
-- view — a second schema to show what a *.* grant exposes, and one account per
-- grant combination under test. Load it into a disposable MySQL as root:
--
--   docker run -d --name grants-probe -e MYSQL_ROOT_PASSWORD=root -p 127.0.0.1:33084:3306 mysql:8.4.4
--   docker exec -i grants-probe mysql -uroot -proot < src/test/resources/grant-profiles-probe.sql
--   GRANTS_PROBE_URL=jdbc:mysql://127.0.0.1:33084/ ./gradlew test --tests '*GrantProfilesProbeTest' --rerun -i

CREATE DATABASE app;
CREATE DATABASE other;
CREATE TABLE app.customer (
  id INT PRIMARY KEY,
  email VARCHAR(100) NOT NULL COMMENT 'login address',
  UNIQUE KEY uk_email (email)
) COMMENT 'people who pay';
CREATE TABLE app.orders (
  id INT PRIMARY KEY,
  customer_id INT NOT NULL,
  total DECIMAL(10,2),
  KEY ix_customer (customer_id),
  CONSTRAINT fk_orders_customer FOREIGN KEY (customer_id) REFERENCES app.customer (id)
);
CREATE VIEW app.customer_orders AS
  SELECT c.email, o.total FROM app.customer c JOIN app.orders o ON o.customer_id = c.id;
CREATE TABLE other.secret (id INT PRIMARY KEY, v VARCHAR(10));
INSERT INTO app.customer VALUES (1, 'a@example.com');
INSERT INTO app.orders VALUES (1, 1, 9.99);
-- so the digest table has rows to capture
SELECT * FROM app.customer_orders WHERE total > 1;

-- every account gets the two capture grants; they differ only on `app`
CREATE USER p_select@'%' IDENTIFIED BY 'pw';
CREATE USER p_showview@'%' IDENTIFIED BY 'pw';
CREATE USER p_showview_global@'%' IDENTIFIED BY 'pw';
CREATE USER p_select_showview@'%' IDENTIFIED BY 'pw';
CREATE USER p_references@'%' IDENTIFIED BY 'pw';
CREATE USER p_references_showview@'%' IDENTIFIED BY 'pw';

GRANT SELECT ON sys.* TO p_select@'%', p_showview@'%', p_showview_global@'%',
  p_select_showview@'%', p_references@'%', p_references_showview@'%';
GRANT SELECT ON performance_schema.* TO p_select@'%', p_showview@'%', p_showview_global@'%',
  p_select_showview@'%', p_references@'%', p_references_showview@'%';

GRANT SELECT ON app.* TO p_select@'%';
GRANT SHOW VIEW ON app.* TO p_showview@'%';
GRANT SHOW VIEW ON *.* TO p_showview_global@'%';
GRANT SELECT, SHOW VIEW ON app.* TO p_select_showview@'%';
GRANT REFERENCES ON app.* TO p_references@'%';
GRANT REFERENCES, SHOW VIEW ON app.* TO p_references_showview@'%';
