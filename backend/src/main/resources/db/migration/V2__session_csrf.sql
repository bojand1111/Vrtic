-- Web sessions use a readable double-submit CSRF token bound to the session.
ALTER TABLE app.sessions ADD COLUMN csrf_token_hash bytea;
