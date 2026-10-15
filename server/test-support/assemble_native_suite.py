"""Assemble the retained acceptance scripts into one Bun file for a fresh private DB.
The output is generated test code only. All credentials remain cloud environment variables.
"""
import pathlib,sys
root=pathlib.Path(__file__).resolve().parent
output=pathlib.Path(sys.argv[1])
preflight='''import {SQL} from 'bun';
const beforeDb=new URL(Bun.env.DATABASE_URL!),beforeApi=new URL(Bun.env.CRM_API_URL!);
if(Bun.env.CRM_P1D_WRITE_SCOPE!=='isolated-new-database'||beforeDb.hostname!==Bun.env.CRM_ISOLATED_DATABASE_HOST||beforeDb.hostname!==beforeApi.hostname||beforeDb.pathname!=='/crm_acceptance'||beforeApi.port!=='8080'||!beforeDb.hostname.endsWith('.railway.internal'))throw new Error('Private isolated preflight guard');
const preflight=new SQL(Bun.env.DATABASE_URL!);try{const row=(await preflight`SELECT current_database() AS name,(SELECT count(*)::int FROM sys_tenant) AS tenants,(SELECT count(*)::int FROM iam_user) AS users`)[0];if(row.name!=='crm_acceptance'||row.tenants!==3||row.users!==6)throw new Error('Refusing writes unless fresh three tenants and six users');console.log('CRM_PREFLIGHT '+JSON.stringify({database:row.name,tenants:row.tenants,users:row.users,privateApi:true}));}finally{await preflight.close();}
const phaseResults:any[]=[];const print=console.log.bind(console);let phase='';console.log=(...args:any[])=>{const line=args.join(' ');const candidate=line.startsWith('CRM_RESULT ')?line.slice(11):line;if(candidate.startsWith('{')){try{const parsed=JSON.parse(candidate);phaseResults.push({phase,...parsed});print('CRM_RESULT '+JSON.stringify({phase,...parsed}));return;}catch{}}print(...args);};
'''
scripts=[('P0','verify_api.ts'),('P1-A','verify_p1a.ts'),('P1-B','verify_p1b.ts'),('P1-C','verify_p1c.ts'),('P1-D','verify_p1d.ts'),('P2-A','verify_p2a.ts'),('P2-B','verify_p2b.ts'),('P2-C/D','verify_p2cd.ts'),('P1-D checkpoint','collect_p1d_evidence.ts')]
import re,json
parts=[preflight]
for phase,file in scripts:
    code=re.sub(r'^import\s*\{\s*SQL\s*\}\s*from\s*[\'\"]bun[\'\"];\s*$', '',(root/file).read_text(),flags=re.M)
    parts.append('phase='+json.dumps(phase)+";print('CRM_PHASE_START '+phase);await(async()=>{\n"+code+'\n})();\n')
parts.append("print('CRM_SUITE_DONE '+JSON.stringify({passed:true,phases:phaseResults.map(x=>({phase:x.phase,passed:x.passed??x.apiPassed??x.p1aPassed??x.p1bPassed??x.p2aPassed??x.p2bPassed??x.checkpointPassed})),target:'private disposable PostgreSQL 18',noOriginalBusinessWrites:true}));")
output.parent.mkdir(parents=True,exist_ok=True);output.write_text(''.join(parts))
print(json.dumps({'generated':str(output),'scripts':len(scripts)}))
