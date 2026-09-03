CREATE TABLE migrated_items (
    id INTEGER PRIMARY KEY,
    value TEXT NOT NULL
);

INSERT INTO migrated_items(id, value) VALUES (1, 'hello; migration');
