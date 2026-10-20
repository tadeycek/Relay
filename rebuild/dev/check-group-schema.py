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

# v19 group avatars and per-actor reaction revisions preserve older rows and removal high-water marks.
avatars=(ROOT/'app/src/main/java/com/relay/app/groups/GroupAvatars.kt').read_text()
reactions=(ROOT/'app/src/main/java/com/relay/app/messaging/ConversationEvents.kt').read_text()
db.execute(re.search(r'const val CREATE = "(.*?)"',avatars).group(1))
db.execute(re.search(r'const val CREATE = "(.*?)"',reactions).group(1))
avatar_sql=re.search(r'execSQL\("(INSERT INTO group_avatars.*?)"',avatars).group(1)
for revision,jpeg in [(2,b'new'),(1,b'old'),(3,None),(2,b'replay')]:
    db.execute(avatar_sql,(3,revision,jpeg))
assert db.execute('SELECT revision,jpeg FROM group_avatars').fetchone()==(3,None)
reaction_sql=re.search(r'execSQL\("(INSERT INTO reactions.*?)"',reactions).group(1)
for revision,emoji in [(2,'like'),(1,'old'),(3,''),(2,'replay')]:
    db.execute(reaction_sql,('group:3','message','alice',revision,emoji,100))
db.execute(reaction_sql,('group:3','message','bob',1,'other',100))
db.execute(reaction_sql,('direct:3','message','alice',1,'independent',100))
assert db.execute("SELECT revision,emoji FROM reactions WHERE scope='group:3' AND actor='alice'").fetchone()==(3,'')
assert db.execute("SELECT emoji FROM reactions WHERE scope='group:3' AND actor='bob'").fetchone()==('other',)
assert db.execute("SELECT emoji FROM reactions WHERE scope='direct:3' AND actor='alice'").fetchone()==('independent',)
db.execute('DELETE FROM groups WHERE _id=3')
assert db.execute('SELECT count(*) FROM group_avatars').fetchone()==(0,)
print('Host SQLite: v19 avatar/reaction revisions, removal replay rejection, actor/conversation isolation and avatar cascade pass.')

# v19 -> v20: direct group delivery. Outbox entries may target a group member who isn't a contact, so the
# table is rebuilt with an optional contact_id; queued entries must survive with ids, attempts and state.
v19_outbox = '''CREATE TABLE outbox (
    _id INTEGER PRIMARY KEY AUTOINCREMENT,
    contact_id INTEGER NOT NULL REFERENCES contacts(_id) ON DELETE CASCADE,
    recipient_pubkey TEXT NOT NULL, payload_id TEXT NOT NULL, payload_json TEXT NOT NULL,
    created_at INTEGER NOT NULL, attempts INTEGER NOT NULL DEFAULT 0, next_attempt_at INTEGER NOT NULL,
    state INTEGER NOT NULL DEFAULT 0)'''
db.execute("INSERT INTO contacts(_id,name,phone) VALUES (7,'Alice','nostr:alice')")
db.execute(v19_outbox)
db.execute(sql('Outbox', 'INDEX_DUE'))
rows = [(1,7,'alice','p1','{"a":1}',10,3,99,0), (2,7,'alice','p2','{"b":2}',11,0,11,1)]
db.executemany('INSERT INTO outbox VALUES (?,?,?,?,?,?,?,?,?)', rows)
db.commit()
helper = (ROOT / 'app/src/main/java/com/relay/app/data/db/RelayDbHelper.kt').read_text()
block = re.search(r'if \(oldVersion < 20\) \{(.*?)\n        \}', helper, re.S).group(1)
pending_create = re.search(r'const val CREATE_PENDING = """(.*?)"""', shared, re.S).group(1)
statements = re.findall(r'db\.execSQL\(([\w.]+)\)', block)
assert statements[-1].endswith('SharedGroups.CREATE_PENDING') and len(statements) == 6
with db:
    for stmt in statements:
        db.execute(pending_create if stmt.endswith('CREATE_PENDING') else sql('Outbox', stmt.split('.')[-1]))
assert db.execute('SELECT _id,contact_id,recipient_pubkey,payload_id,payload_json,created_at,attempts,next_attempt_at,state FROM outbox ORDER BY _id').fetchall() == rows
assert db.execute("SELECT 1 FROM sqlite_master WHERE name='outbox_v19'").fetchone() is None
assert db.execute("SELECT tbl_name FROM sqlite_master WHERE type='index' AND name='idx_outbox_due'").fetchone() == ('outbox',)
db.execute("INSERT INTO outbox(contact_id,recipient_pubkey,payload_id,payload_json,created_at,next_attempt_at,relay_hints) VALUES (NULL,'bob','p3','{}',12,12,'wss://a.example')")
assert db.execute("SELECT _id,relay_hints FROM outbox WHERE payload_id='p3'").fetchone() == (3,'wss://a.example')
db.execute('DELETE FROM contacts WHERE _id=7')
assert db.execute('SELECT payload_id FROM outbox').fetchall() == [('p3',)]
print('Host SQLite: v20 outbox rebuild keeps queued entries, ids and the due index; member entries need no contact; contact cascade still applies.')

# Events held until their roster arrives are bounded per group, overall, and by age.
evict_group = re.search(r'const val PENDING_EVICT_GROUP = "(.*?)"\n', shared).group(1)
evict_all = re.search(r'const val PENDING_EVICT_ALL = "(.*?)"\n', shared).group(1)
db.execute("INSERT INTO shared_group_pending VALUES ('owner','g2','s','other',50)")
for i in range(5):
    db.execute(evict_group, ('owner', 'g1', 2))
    db.execute(evict_all, (0, 99))
    db.execute("INSERT INTO shared_group_pending VALUES ('owner','g1','s',?,?)", ('b%d' % i, 100 + i))
assert [r[0] for r in db.execute("SELECT body FROM shared_group_pending WHERE shared_id='g1' ORDER BY received_at")] == ['b2','b3','b4']
assert db.execute("SELECT body FROM shared_group_pending WHERE shared_id='g2'").fetchall() == [('other',)]
db.execute(evict_all, (0, 2))
assert [r[0] for r in db.execute('SELECT body FROM shared_group_pending ORDER BY received_at')] == ['b3','b4']
db.execute(evict_all, (104, 99))
assert [r[0] for r in db.execute('SELECT body FROM shared_group_pending')] == ['b4']
print('Host SQLite: held group events are capped per group and overall, evict oldest first, and expire.')

# v21: actual old-v20 reaction table migrates additively; stale activity never regresses.
v21 = sqlite3.connect(':memory:')
v21.execute('CREATE TABLE reactions (scope TEXT,target TEXT,actor TEXT,revision INTEGER,emoji TEXT,PRIMARY KEY(scope,target,actor))')
v21.execute("INSERT INTO reactions VALUES ('direct:1','photo','alice',3,'')")
v21.execute('CREATE TABLE messages (_id INTEGER PRIMARY KEY,contact_id INTEGER,msg_id TEXT,timestamp INTEGER,media_uri TEXT,is_sent INTEGER DEFAULT 1)')
v21.execute('CREATE TABLE group_messages (_id INTEGER PRIMARY KEY,group_id INTEGER,event_id TEXT,timestamp INTEGER,media_uri TEXT,is_sent INTEGER DEFAULT 1)')
v21.execute('CREATE TABLE group_delivery (group_id INTEGER,event TEXT,payload_id TEXT)')
v21.execute('CREATE TABLE outbox (payload_id TEXT,created_at INTEGER)')
v21.execute('CREATE TABLE contacts (_id INTEGER PRIMARY KEY,name TEXT)')
v21.execute('CREATE TABLE shared_groups (group_id INTEGER,state TEXT)')
v21.execute("INSERT INTO contacts VALUES (1,'Alias kept')")
v21.execute("INSERT INTO shared_groups VALUES (1,'roster kept')")
block21=re.search(r'if \(oldVersion < 21\) \{(.*?)\n        \}',helper,re.S).group(1)
v21.execute(re.search(r'ALTER TABLE reactions[^"\n]*',block21).group(0))
assert v21.execute('SELECT revision,emoji FROM reactions').fetchone()==(3,'')
retired=(ROOT/'app/src/main/java/com/relay/app/messaging/RetiredActivity.kt').read_text()
v21.execute(re.search(r'const val LEGACY_CREATE = "(.*?)"',retired).group(1))
v21.execute("INSERT INTO contact_activity VALUES ('alice',101,1)")
assert 'if (oldVersion < 24) db.execSQL(com.relay.app.messaging.RetiredActivity.DROP)' in helper
v21.execute(re.search(r'const val DROP = "(.*?)"',retired).group(1))
assert v21.execute("SELECT name FROM sqlite_master WHERE name='contact_activity'").fetchone() is None
assert v21.execute('SELECT name FROM contacts').fetchall()==[('Alias kept',)]
assert v21.execute('SELECT state FROM shared_groups').fetchall()==[('roster kept',)]
# Expiring a group message must cancel only that group's fanout, including colliding event ids elsewhere.
v21.executemany('INSERT INTO messages(_id,contact_id,msg_id,timestamp,media_uri) VALUES (?,?,?,?,?)',[(1,1,'old-direct',100,'private-old'),(2,1,'fresh-direct',101,'private-new')])
v21.executemany('INSERT INTO group_messages(_id,group_id,event_id,timestamp,media_uri) VALUES (?,?,?,?,?)',[(1,1,'collision',100,'old-group'),(2,2,'collision',101,'fresh-group')])
v21.executemany('INSERT INTO group_delivery VALUES (?,?,?)',[(1,'collision','old-leg'),(2,'collision','fresh-leg')])
v21.executemany('INSERT INTO outbox VALUES (?,?)', [('old-direct',101),('fresh-direct',101),('old-leg',101),('fresh-leg',101)])
expiry=re.search(r'fun expire\(.*?\n    private fun remove',actions,re.S).group(0)
for query in re.findall(r'db.execSQL\("(DELETE FROM (?:outbox|group_delivery).*?)"',expiry):
    v21.execute(query,tuple(100 for _ in range(query.count('?'))))
v21.execute('DELETE FROM messages WHERE timestamp<=?',(100,))
v21.execute('DELETE FROM group_messages WHERE timestamp<=?',(100,))
assert set(v21.execute('SELECT payload_id FROM outbox'))=={('fresh-direct',),('fresh-leg',)}
assert v21.execute('SELECT msg_id FROM messages').fetchall()==[('fresh-direct',)]
assert v21.execute('SELECT group_id FROM group_messages').fetchall()==[(2,)]
assert v21.execute('SELECT name FROM contacts').fetchone()==('Alias kept',)
assert v21.execute('SELECT state FROM shared_groups').fetchone()==('roster kept',)
print('Host SQLite: v21 reaction preservation, v24 retired-activity removal and scoped seven-day message/outbox expiry preserve new content, aliases and rosters.')
