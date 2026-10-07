#!/usr/bin/env node
// Build exact product ownership identities from the final, injected snapshot archive.
import { createHash } from 'node:crypto'
import { spawnSync } from 'node:child_process'
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs'
import { dirname, resolve } from 'node:path'

const args = process.argv.slice(2)
const value = (flag) => { const i = args.indexOf(flag); return i < 0 ? '' : args[i + 1] ?? '' }
const snapshot = resolve(value('--snapshot'))
const output = resolve(value('--out'))
if (!value('--snapshot') || !value('--out')) { console.error('usage: build-hard-manifest.mjs --snapshot <final.tar.xz> --out <manifest.json>'); process.exit(2) }
const py = String.raw`import json,sys,tarfile
archive,out=sys.argv[1:3]
names=[]
# Python's bundled tarfile reader is used here for Windows/portable compatibility; it scans
# the compressed archive once (single-threaded xz) and only materializes the three small YAMLs.
# Do not call extract() or seek the xz stream again; release timing can guide a later tar+xz path.
with tarfile.open(archive,'r:xz') as tf:
  members={m.name.lstrip('./'):m for m in tf.getmembers() if m.isfile()}
  targets=[
    'usr/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-base/cordis.patch.yml',
    'usr/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-web-app/cordis.patch.yml',
    'home/.dsh/profiles/web/cordis.patch.yml',
  ]
  missing=[path for path in targets if path not in members]
  if missing: raise SystemExit('final snapshot lacks complete Cordis composition inputs: '+', '.join(missing))
  for path in sorted(set(targets)):
    text=tf.extractfile(members[path]).read().decode('utf-8')
    lines=text.splitlines()
    i=0
    while i<len(lines):
      if lines[i].strip()!='- insert:': i+=1; continue
      end=i+1
      while end<len(lines) and not (lines[end].startswith('- ') or lines[end]=='-'): end+=1
      rows=[]
      for j in range(i+1,end):
        import re
        m=re.match(r'^(\s*)-\s+(id|name):\s*(.*)$',lines[j])
        if m: rows.append((j,len(m.group(1))))
      if rows:
        indent=min(w for _,w in rows)
        starts=[j for j,w in rows if w==indent]
        for k,start in enumerate(starts):
          stop=starts[k+1] if k+1<len(starts) else end
          import re
          head=re.match(r'^\s*-\s+(id|name):\s*(.*)$',lines[start])
          ident=head.group(2).strip().strip("'").strip('"') if head.group(1)=='id' else None
          module=head.group(2).strip().strip("'").strip('"') if head.group(1)=='name' else None
          for row in lines[start+1:stop]:
            m=re.match(r'^\s{'+str(indent+2)+r'}name:\s*(.*)$',row)
            if m and not module: module=m.group(1).strip().strip("'").strip('"')
          if module:
            if not ident: raise SystemExit('product insert identity has a name but no exact id: '+path+':'+str(start+1))
            names.append({'id':ident,'name':module,'source':path})
      i=end
entries={ (row['id'],row['name']):row for row in names }
if not entries: raise SystemExit('no plugin insert identities found in final snapshot')
hard=[{'id':row['id'],'name':row['name']} for row in entries.values()]
profile=[{'id':row['id'],'name':row['name']} for row in entries.values() if row['source']=='home/.dsh/profiles/web/cordis.patch.yml']
print(json.dumps({'entries':sorted(hard,key=lambda x:(x['name'],x['id'] or '')),'profileEntries':sorted(profile,key=lambda x:(x['name'],x['id'] or ''))},separators=(',',':')))`
const python = process.platform === 'win32' ? 'python' : 'python3'
const extracted = spawnSync(python, ['-c', py, snapshot, output], { encoding: 'utf8' })
if (extracted.status !== 0) {
  process.stderr.write(extracted.stderr || extracted.stdout || 'manifest extraction failed\n')
  process.exit(extracted.status ?? 1)
}
const result = JSON.parse(extracted.stdout)
const fingerprint = createHash('sha256').update(readFileSync(snapshot)).digest('hex')
const manifest = { schema: 2, complete: true, fingerprint, entries: result.entries, profileEntries: result.profileEntries,
  names: [...new Set(result.entries.map((entry) => entry.name))].sort() }
mkdirSync(dirname(output), { recursive: true })
writeFileSync(output, JSON.stringify(manifest) + '\n', 'utf8')
console.log(`hard manifest: ${manifest.entries.length} exact identities; snapshot=${fingerprint}`)
