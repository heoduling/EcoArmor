"""Summarize complete-process old/new runs, never the mixed-classloader experiment."""
from pathlib import Path
from statistics import median
from collections import defaultdict
import re,json,sys

root=Path(sys.argv[1])
runs={'a1-final':'old','b1-final':'new','b2-final':'new','a2-final':'old'}
pattern=re.compile(r'\[EA\] BENCH fixture=(\S+) variant=(old|new) round=(\d+) count=(\d+) ns=(\d+) bytes=(\d+)')
groups=defaultdict(lambda:defaultdict(list));checks={};per_run={}
for label,variant in runs.items():
    text=(root/(label+'-server.log')).read_text(encoding='utf8')
    assert '[EA] FAIL' not in text and '[EA] BENCH_DONE' in text and 'All RegionFile I/O tasks to complete' in text
    assert re.search(r'\[EA\] DISABLED checks=\d+ failures=0',text)
    rows=pattern.findall(text);assert len(rows)==9*16,(label,len(rows))
    checks[label]=int(re.search(r'REGRESSION_DONE checks=(\d+)',text)[1])
    data=defaultdict(list)
    for fixture,actual,round_,count,ns,bytes_ in rows:
        assert actual==variant
        sample={'run':label,'round':int(round_),'count':int(count),'ns_per_call':int(ns)/int(count),'bytes_per_call':int(bytes_)/int(count)}
        groups[fixture][variant].append(sample);data[fixture].append(sample)
    per_run[label]={f:{'ns':median(s['ns_per_call'] for s in samples),'bytes':median(s['bytes_per_call'] for s in samples)} for f,samples in data.items()}
result={}
for fixture,variants in groups.items():
    row={}
    for variant,samples in variants.items():
        assert len(samples)==32
        row[variant]={'ns_per_call':median(s['ns_per_call'] for s in samples),'bytes_per_call':median(s['bytes_per_call'] for s in samples),'samples':len(samples),'calls':sum(s['count'] for s in samples)}
    row['allocation_reduction_percent']=(1-row['new']['bytes_per_call']/row['old']['bytes_per_call'])*100
    row['wall_time_reduction_percent']=(1-row['new']['ns_per_call']/row['old']['ns_per_call'])*100
    result[fixture]=row
report={'regression_checks':checks,'measured_calls':4*9*16*5000,'warmup_calls':4*9*12000,'per_fixture':result,'per_run':per_run}
(root/'results.json').write_text(json.dumps(report,indent=2),encoding='utf8')
for f,r in result.items():
    print(f"{f}: {r['old']['bytes_per_call']:.1f} -> {r['new']['bytes_per_call']:.1f} B/call ({r['allocation_reduction_percent']:.2f}% less); {r['old']['ns_per_call']:.1f} -> {r['new']['ns_per_call']:.1f} ns/call ({r['wall_time_reduction_percent']:.2f}% less)")
print('regression checks',checks,'measured calls',report['measured_calls'])
