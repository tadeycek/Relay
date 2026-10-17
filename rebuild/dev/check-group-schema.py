#!/usr/bin/env python3
"""Host SQLite checks for the additive shared-group schema; not an Android SQLCipher test."""
import re
import sqlite3
from pathlib import Path
ROOT = Path(__file__).resolve().parents[2]
source = (ROOT / 'app/src/main/java/com/relay/app/data/db/DatabaseContract.kt').read_text()
objects = {}
for match in re.finditer(r'    object (\w+) \{(.*?)(?=\n    object |\n\})', source, re.S):
    name, body = match.groups()
    values = {}
    for m in re.finditer(r'const val (\w+) = ("""(.*?)"""|"([^"\n]*)")', body, re.S):
        values[m.group(1)] = m.group(3) if m.group(3) is not None else m.group(4)
    objects[name] = values

def sql(obj, field='CREATE'):
    def value(m):
        ident = m.group(1) or m.group(2)
        a, b = ident.split('.') if '.' in ident else (obj, ident)
        return objects[a][b]
    return re.sub(r'\$\{([^}]+)\}|\$(\w+)', value, objects[obj][field])

shared = (ROOT / 'app/src/main/java/com/relay/app/groups/SharedGroups.kt').read_text()
create = re.search(r'const val CREATE = """(.*?)"""', shared, re.S).group(1)
seen = re.search(r'const val CREATE_SEEN = """(.*?)"""', shared, re.S).group(1)
db = sqlite3.connect(':memory:')
db.execute('PRAGMA foreign_keys = ON')
for obj in ['Contacts', 'Groups', 'GroupMembers', 'GroupMessages']:
    db.execute(sql(obj))
# Existing v15 content survives the exact additive v16 statements.
db.execute("INSERT INTO groups(name) VALUES ('Existing group')")
db.execute("INSERT INTO group_messages(group_id,body,timestamp) VALUES (1,'Existing message',1)")
db.execute(create); db.execute(seen)
db.execute('ALTER TABLE group_messages ADD COLUMN sender_key TEXT')
db.execute('ALTER TABLE group_messages ADD COLUMN sender_name TEXT')
assert db.execute('SELECT name FROM groups WHERE _id=1').fetchone()[0] == 'Existing group'
assert db.execute('SELECT body,sender_key,sender_name FROM group_messages WHERE _id=1').fetchone() == ('Existing message',None,None)
db.execute("INSERT INTO shared_groups(group_id,owner,shared_id,state) VALUES (1,'owner','uuid','state')")
db.execute("INSERT INTO shared_group_seen VALUES (1,'event')")
assert db.execute("INSERT OR IGNORE INTO shared_group_seen VALUES (1,'event')").rowcount == 0
assert db.execute('SELECT count(*) FROM shared_group_seen').fetchone()[0] == 1
db.commit()
try:
    with db:
        db.execute("UPDATE shared_groups SET state='changed' WHERE group_id=1")
        raise RuntimeError('Simulated outbox failure')
except RuntimeError:
    pass
assert db.execute('SELECT state FROM shared_groups').fetchone()[0] == 'state'
db.execute('DELETE FROM groups WHERE _id=1')
for table in ['shared_groups', 'shared_group_seen', 'group_messages']:
    assert db.execute('SELECT count(*) FROM '+table).fetchone()[0] == 0
print('Host SQLite: additive migration preserves legacy data; unique group events, transaction rollback and foreign-key cleanup pass.')
