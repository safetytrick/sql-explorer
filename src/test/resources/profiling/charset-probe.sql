DROP DATABASE IF EXISTS app_charset_probe;
CREATE DATABASE app_charset_probe;
USE app_charset_probe;
SET NAMES utf8mb4;
SET SESSION cte_max_recursion_depth = 100000;

CREATE TABLE app_note (
  id    BIGINT NOT NULL,
  title VARCHAR(100) CHARACTER SET latin1,
  body  TEXT CHARACTER SET utf8mb4,
  code  CHAR(8) CHARACTER SET ascii,
  PRIMARY KEY (id)
);

INSERT INTO app_note (id, title, body, code)
WITH RECURSIVE n (i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < 60000)
SELECT i, 'plain title', 'plain body', 'AB12' FROM n;

UPDATE app_note SET title = X'636166C3A9' WHERE id IN (1, 31000, 59990);
UPDATE app_note SET title = X'636166E9' WHERE id IN (2, 4000, 59991);
UPDATE app_note SET title = X'93717565746594' WHERE id = 3;

UPDATE app_note SET body = 'cafÃ© au lait' WHERE id IN (3, 22000, 59999);
UPDATE app_note SET body = 'donâ€™t' WHERE id IN (4, 40000);
UPDATE app_note SET body = 'MÃ¼ller' WHERE id = 5;
UPDATE app_note SET body = 'cafÃƒÂ©' WHERE id = 6;
UPDATE app_note SET body = CONCAT('caf', _utf16 X'FFFD') WHERE id = 7;
UPDATE app_note SET body = CONCAT(_utf16 X'0093', 'quoted', _utf16 X'0094') WHERE id = 8;
UPDATE app_note SET body = 'Jos? Garc?a' WHERE id = 9;
UPDATE app_note SET body = 'naïve, correctly stored' WHERE id = 10;

CREATE TABLE app_small (
  id   INT NOT NULL,
  name VARCHAR(40) CHARACTER SET utf8mb4,
  PRIMARY KEY (id)
);
INSERT INTO app_small (id, name)
WITH RECURSIVE n (i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < 40) SELECT i, 'name' FROM n;
UPDATE app_small SET name = 'Ã©tÃ©' WHERE id = 12;

ANALYZE TABLE app_note, app_small;
