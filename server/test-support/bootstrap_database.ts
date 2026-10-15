// One-off database initialization. Credentials are supplied through private cloud variables.
import { SQL } from 'bun';
const sql=new SQL(Bun.env.DATABASE_URL!);
const quote=(value:string)=>"'"+value.replaceAll("'","''")+"'";
for(const role of ['crm_runtime','crm_migrator']){
 const password=Bun.env[role==='crm_runtime'?'CRM_RUNTIME_PASSWORD':'CRM_MIGRATOR_PASSWORD']!;
 if(!/^[a-f0-9]{48}$/.test(password))throw new Error('Missing generated database credential');
 const found=await sql`SELECT rolname,rolsuper,rolbypassrls FROM pg_roles WHERE rolname=${role}`;
 if(found.length===0)await sql.unsafe('CREATE ROLE '+role+' LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS PASSWORD '+quote(password));
 else if(found[0].rolsuper||found[0].rolbypassrls)throw new Error('Unsafe pre-existing database role');
}
await sql.unsafe('REVOKE CREATE ON SCHEMA public FROM PUBLIC');
await sql.unsafe('GRANT USAGE,CREATE ON SCHEMA public TO crm_migrator');
await sql.unsafe('GRANT USAGE ON SCHEMA public TO crm_runtime');
console.log('Database roles prepared');
await sql.close();
