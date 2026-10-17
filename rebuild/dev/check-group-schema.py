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

# v16 -> v17: stable event ids and author-scoped deletion tombstones.
actions = (ROOT / 'app/src/main/java/com/relay/app/messaging/MessageDeletion.kt').read_text()
delete_schema = re.search(r'const val CREATE = "(.*?)"', actions).group(1)
db.execute('ALTER TABLE group_messages ADD COLUMN event_id TEXT')
db.execute('ALTER TABLE group_messages ADD COLUMN media_ref TEXT')
db.execute('CREATE INDEX group_event ON group_messages(group_id,event_id)')
db.execute(delete_schema)
db.execute("INSERT INTO groups(_id,name) VALUES (2,'New group')")
db.execute("INSERT INTO group_messages(group_id,body,timestamp,sender_key,event_id) VALUES (2,'Alice',1,'alice','same-id')")
db.execute("INSERT INTO group_messages(group_id,body,timestamp,sender_key,event_id) VALUES (2,'Bob',1,'bob','same-id')")
db.execute("INSERT INTO message_deletions VALUES ('group:2','alice','same-id')")
assert db.execute("INSERT OR IGNORE INTO message_deletions VALUES ('group:2','alice','same-id')").rowcount == 0
where = re.search(r'remove\(context, "group_messages", "([^"]*event_id[^\"]*)"', actions).group(1)
db.execute('DELETE FROM group_messages WHERE ' + where, ('2','same-id','alice'))
assert db.execute('SELECT body FROM group_messages WHERE group_id=2').fetchall() == [('Bob',)]
assert db.execute("SELECT 1 FROM message_deletions WHERE scope=? AND author=? AND event=?", ('group:2','alice','same-id')).fetchone()
assert db.execute("SELECT 1 FROM message_deletions WHERE scope=? AND author=? AND event=?", ('group:3','alice','same-id')).fetchone() is None
assert db.execute("SELECT 1 FROM message_deletions WHERE scope=? AND author=? AND event=?", ('group:2','bob','same-id')).fetchone() is None
print('Host SQLite: v17 event migration, deletion deduplication, cross-author/cross-group isolation and late-delivery tombstones pass.')
purges_schema = re.search(r'const val CREATE_PURGES = "(.*?)"', actions).group(1)
db.execute(purges_schema)
db.execute("INSERT OR IGNORE INTO message_purges VALUES ('group:2','alice',100)")
db.execute("UPDATE message_purges SET before_ms=MAX(before_ms,?) WHERE scope=? AND author=?", (50,'group:2','alice'))
assert db.execute("SELECT before_ms FROM message_purges WHERE scope='group:2' AND author='alice'").fetchone() == (100,)
assert db.execute("SELECT 1 FROM message_purges WHERE scope=? AND author=? AND before_ms>=?", ('group:2','alice',99)).fetchone()
assert db.execute("SELECT 1 FROM message_purges WHERE scope=? AND author=? AND before_ms>=?", ('group:2','alice',101)).fetchone() is None
print('Host SQLite: history purge cutoff is monotonic; late old messages stay deleted while newer messages remain admissible.')

# v18 profiles are independent of membership and have a monotonic revision high-water mark.
profiles = (ROOT / 'app/src/main/java/com/relay/app/groups/GroupProfiles.kt').read_text()
delivery = (ROOT / 'app/src/main/java/com/relay/app/groups/GroupDelivery.kt').read_text()
for code in [profiles, delivery]:
    db.execute(re.search(r'const val CREATE = """(.*?)"""', code, re.S).group(1))
upsert = re.search(r'execSQL\("""(INSERT INTO group_profiles.*?)"""', profiles, re.S).group(1)
for revision, name in [(2,'New'), (1,'Old'), (2,'Duplicate old'), (3,'Newest')]:
    db.execute(upsert, (2,'alice',revision,name))
    expected = (2,'New') if revision <= 2 else (3,'Newest')
    assert db.execute('SELECT revision,name FROM group_profiles WHERE group_id=2 AND member_key="alice"').fetchone() == expected
assert db.execute('SELECT name FROM groups WHERE _id=2').fetchone() == ('New group',)
assert db.execute('SELECT body FROM group_messages WHERE group_id=2').fetchall() == [('Bob',)]
db.execute("INSERT INTO group_delivery VALUES (2,'event','payload',1)")
db.commit()
try:
    with db:
        db.execute("INSERT INTO group_delivery VALUES (2,'rolled-back','another',1)")
        raise RuntimeError('Queue rollback')
except RuntimeError:
    pass
assert db.execute('SELECT count(*) FROM group_delivery').fetchone() == (1,)
db.execute('DELETE FROM groups WHERE _id=2')
for table in ['group_profiles','group_delivery']:
    assert db.execute('SELECT count(*) FROM '+table).fetchone() == (0,)
print('Host SQLite: v18 additive migration, stale/duplicate profile rejection, membership/history preservation, delivery rollback and cascades pass.')

pending = (ROOT / 'app/src/main/java/com/relay/app/media/PendingAttachments.kt').read_text()
db.execute(re.search(r'const val CREATE = """(.*?)"""', pending, re.S).group(1))
db.execute("INSERT INTO groups(_id,name) VALUES (3,'Pending media')")
db.execute("INSERT INTO group_messages(group_id,body,timestamp,event_id) VALUES (3,'caption',1,'pending-event')")
db.commit()
try:
    with db:
        db.execute("INSERT INTO pending_attachments(id,target,is_group,row_id,path,mime,caption,created_at) VALUES ('job',3,1,1,'encrypted','image/jpeg','caption',1)")
        raise RuntimeError('Acceptance rollback')
except RuntimeError:
    pass
assert db.execute('SELECT count(*) FROM pending_attachments').fetchone() == (0,)
with db:
    db.execute("INSERT INTO pending_attachments(id,target,is_group,row_id,path,mime,caption,created_at) VALUES ('job',3,1,1,'encrypted','image/jpeg','caption',1)")
assert db.execute('SELECT id,next_at,attempts FROM pending_attachments').fetchone() == ('job',0,0)
print('Host SQLite: durable attachment-job schema, commit/rollback, and restart-readable retry state pass.')
