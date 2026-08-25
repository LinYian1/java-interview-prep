CREATE TABLE IF NOT EXISTS category (
    id   INTEGER PRIMARY KEY,
    name TEXT NOT NULL,
    ord  INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS question (
    id           TEXT PRIMARY KEY,
    category_id  INTEGER NOT NULL,
    num          INTEGER NOT NULL,
    title        TEXT NOT NULL,
    answer_md    TEXT NOT NULL,
    title_hash   TEXT NOT NULL,
    content_hash TEXT NOT NULL,
    keywords_json TEXT NOT NULL DEFAULT '[]',
    updated_at   TEXT NOT NULL DEFAULT (datetime('now', 'localtime'))
);
CREATE INDEX IF NOT EXISTS idx_question_cat ON question(category_id, num);

CREATE TABLE IF NOT EXISTS gen_content (
    question_id  TEXT PRIMARY KEY,
    what_md      TEXT NOT NULL DEFAULT '',
    why_md       TEXT NOT NULL DEFAULT '',
    how_md       TEXT NOT NULL DEFAULT '',
    source       TEXT NOT NULL DEFAULT 'rule',
    model        TEXT,
    generated_at TEXT NOT NULL DEFAULT (datetime('now', 'localtime'))
);

CREATE TABLE IF NOT EXISTS ai_extra (
    question_id  TEXT PRIMARY KEY,
    insights_json   TEXT NOT NULL DEFAULT '[]',
    followups_json  TEXT NOT NULL DEFAULT '[]',
    generated_at TEXT NOT NULL DEFAULT (datetime('now', 'localtime'))
);

-- level: 0 未学习 / 1 模糊 / 2 已掌握
CREATE TABLE IF NOT EXISTS mastery (
    question_id TEXT PRIMARY KEY,
    level       INTEGER NOT NULL DEFAULT 0,
    updated_at  TEXT NOT NULL DEFAULT (datetime('now', 'localtime'))
);

CREATE TABLE IF NOT EXISTS related (
    question_id TEXT NOT NULL,
    related_id  TEXT NOT NULL,
    score       REAL NOT NULL,
    ord         INTEGER NOT NULL,
    PRIMARY KEY (question_id, related_id)
);

CREATE TABLE IF NOT EXISTS settings (
    key   TEXT PRIMARY KEY,
    value TEXT
);

CREATE TABLE IF NOT EXISTS job_state (
    id         INTEGER PRIMARY KEY CHECK (id = 1),
    type       TEXT,
    status     TEXT,
    total      INTEGER,
    done       INTEGER,
    failed     INTEGER,
    message    TEXT,
    updated_at TEXT NOT NULL DEFAULT (datetime('now', 'localtime'))
);
