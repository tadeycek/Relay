#!/usr/bin/env python3
"""Synthetic host SQLite benchmark. Not a phone, SQLCipher, Compose or relay measurement."""
import json, math, sqlite3, statistics, time
from pathlib import Path
ROOT = Path(__file__).resolve().parents[2]
sqlite = sqlite3.connect(':memory:')
sqlite.execute('CREATE TABLE messages(_id INTEGER PRIMARY KEY,contact_id INTEGER, timestamp INTEGER,body TEXT)')
sqlite.execute('CREATE INDEX history_contact ON messages(contact_id,timestamp DESC,_id DESC)')
sqlite.executemany('INSERT INTO messages VALUES(?,?,?,?)',((i,1,i//4,'Synthetic message '+str(i)) for i in range(1,50001)))
source=(ROOT/'app/src/main/java/com/relay/app/data/repository/HistoryWindow.kt').read_text()
query=source.split('return "',1)[1].split('"',1)[0].replace('$table','messages').replace('$owner','contact_id').replace('$retained','')
baseline='SELECT * FROM messages WHERE contact_id=? ORDER BY timestamp ASC,_id ASC'

def measured(query,args):
    samples=[]
    for _ in range(31):
        start=time.perf_counter_ns()
        result=sqlite.execute(query,args).fetchall()
        samples.append((time.perf_counter_ns()-start)/1e6)
    samples.sort()
    return {'rows':len(result),'p50_ms':round(statistics.median(samples),3),'p95_ms':round(samples[math.ceil(.95*len(samples))-1],3)}

results={'environment':'Synthetic in-memory host SQLite; not Android/SQLCipher/UI/network','messages':50000,
         'before_full_history':measured(baseline,(1,)), 'after_initial_window':measured(query,(1,200))}
latest=sqlite.execute(query,(1,200)).fetchall()
assert len(latest)==200 and latest[-1][0]==50000
first=latest[0]
sqlite.execute("INSERT INTO messages VALUES(50001,1,12501,'new')")
follow=sqlite.execute('SELECT * FROM messages WHERE contact_id=? AND (timestamp>? OR (timestamp=? AND _id>=?)) ORDER BY timestamp ASC,_id ASC',(1,first[2],first[2],first[0])).fetchall()
assert follow[0][0]==first[0] and len(follow)==201
older=sqlite.execute(query,(1,401)).fetchall()
assert {r[0] for r in follow}<={r[0] for r in older}
assert len({r[0] for r in older})==401
results['checks']='Latest window, equal-timestamp order, incoming anchor preservation, older-page expansion: passed'
print(json.dumps(results,indent=2))
