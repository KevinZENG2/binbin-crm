// Run this guard before composing P0, P1-A and P1-B WRITE acceptance scripts.
// The approved target must be a fresh disposable database, not the preview database.
import {SQL as IsolationGuardSQL} from 'bun';
if(Bun.env.CRM_P1A_WRITE_SCOPE!=='isolated-new-database')throw new Error('Isolated scope missing');
const isolatedTarget=new URL(Bun.env.DATABASE_URL!),isolatedApi=new URL(Bun.env.CRM_API_URL!);
if(isolatedTarget.hostname!==Bun.env.CRM_ISOLATED_DATABASE_HOST||isolatedTarget.pathname!=='/crm_acceptance'||isolatedApi.hostname!==isolatedTarget.hostname||isolatedApi.port!=='8080'||!isolatedTarget.hostname.endsWith('.railway.internal'))throw new Error('Isolated private targets required');
const isolatedGuard=new IsolationGuardSQL(Bun.env.DATABASE_URL!);
const isolatedState=await isolatedGuard`SELECT current_database() AS name,(SELECT count(*)::int FROM sys_tenant) AS tenants,(SELECT count(*)::int FROM iam_user) AS users`;
if(isolatedState[0].name!=='crm_acceptance'||isolatedState[0].tenants!==3||isolatedState[0].users!==6)throw new Error('Refusing writes: only a freshly seeded isolated database is accepted');
await isolatedGuard.close();
