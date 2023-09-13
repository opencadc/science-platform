CREATE DATABASE IF NOT EXISTS skaha;
use skaha;

CREATE TABLE IF NOT EXISTS groups (
  gid SERIAL PRIMARY KEY,
  groupname VARCHAR(255) NOT NULL
);

CREATE TABLE IF NOT EXISTS users (
  uid SERIAL PRIMARY KEY,
  username VARCHAR(255) NOT NULL
);

CREATE TABLE IF NOT EXISTS users_groups (
  users_uid INTEGER NOT NULL,
  groups_gid INTEGER NOT NULL,
  PRIMARY KEY (users_uid, groups_gid),
  FOREIGN KEY (users_uid) REFERENCES users (uid),
  FOREIGN KEY (groups_gid) REFERENCES groups (gid)
);