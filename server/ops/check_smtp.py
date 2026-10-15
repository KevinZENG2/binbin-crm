"""TLS/authentication preflight only. Never sends mail or prints credentials."""
import json, os, re, smtplib, ssl
from urllib.parse import urlparse

def settings(env):
    host=env.get('CRM_SMTP_HOST','').strip();sender=env.get('CRM_SMTP_FROM','').strip();user=env.get('CRM_SMTP_USER','').strip();password=env.get('CRM_SMTP_PASSWORD','')
    port=int(env.get('CRM_SMTP_PORT','587'));tls=env.get('CRM_SMTP_TLS','true')=='true';implicit=env.get('CRM_SMTP_SSL','false')=='true';base=urlparse(env.get('CRM_PUBLIC_BASE_URL',''))
    if not re.fullmatch(r'[A-Za-z0-9.-]+',host) or not 1<=port<=65535 or not re.fullmatch(r'[^\s<>@]+@[^\s<>@]+\.[^\s<>@]+',sender) or not (tls^implicit) or not user or not password:
        raise ValueError('SMTP_CONFIG_INCOMPLETE')
    if base.scheme!='https' or not base.hostname or base.username or base.password or base.path or base.query or base.fragment:raise ValueError('PUBLIC_HTTPS_ORIGIN_REQUIRED')
    return host,port,user,password,implicit

def main():
    try:
        host,port,user,password,implicit=settings(os.environ);context=ssl.create_default_context()
        client=smtplib.SMTP_SSL(host,port,timeout=5,context=context) if implicit else smtplib.SMTP(host,port,timeout=5)
        with client:
            client.ehlo()
            if not implicit:client.starttls(context=context);client.ehlo()
            client.login(user,password)
        print('CRM_SMTP_PREFLIGHT '+json.dumps({'tls':True,'authenticated':True,'mailSent':False}))
    except Exception:raise SystemExit('CRM_SMTP_PREFLIGHT_FAILED: configuration, TLS or authentication failed') from None

if __name__=='__main__':main()
