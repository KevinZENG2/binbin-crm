"""Wrap a backup key with the owner's PUBLIC key; unwrap only on their device.
No plaintext key is printed. Private keys/passwords never belong in cloud variables.
"""
import argparse, base64, getpass, hashlib, json, os, pathlib
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, rsa

LABEL=b'binbin-crm-backup-key-v1'

def fingerprint(public):
    return hashlib.sha256(public.public_bytes(serialization.Encoding.DER,serialization.PublicFormat.SubjectPublicKeyInfo)).hexdigest()

def wrap(key,public_pem):
    public=serialization.load_pem_public_key(public_pem)
    if not isinstance(public,rsa.RSAPublicKey) or public.key_size<3072 or len(key)!=32:raise ValueError('RSA_3072_PUBLIC_KEY_AND_AES_256_KEY_REQUIRED')
    encrypted=public.encrypt(key,padding.OAEP(mgf=padding.MGF1(hashes.SHA256()),algorithm=hashes.SHA256(),label=LABEL))
    return {'format':'binbin-crm-backup-key-v1','algorithm':'RSA-OAEP-SHA256','publicKeySha256':fingerprint(public),'backupKeySha256':hashlib.sha256(key).hexdigest(),'wrappedKey':base64.b64encode(encrypted).decode()}

def unwrap(packet,private):
    if packet.get('format')!='binbin-crm-backup-key-v1' or packet.get('algorithm')!='RSA-OAEP-SHA256' or not isinstance(private,rsa.RSAPrivateKey) or private.key_size<3072 or packet['publicKeySha256']!=fingerprint(private.public_key()):raise ValueError('CUSTODY_PACKET_OR_PRIVATE_KEY_INVALID')
    key=private.decrypt(base64.b64decode(packet['wrappedKey'],validate=True),padding.OAEP(mgf=padding.MGF1(hashes.SHA256()),algorithm=hashes.SHA256(),label=LABEL))
    if len(key)!=32 or hashlib.sha256(key).hexdigest()!=packet['backupKeySha256']:raise ValueError('CUSTODY_KEY_HASH_MISMATCH')
    return key

def exclusive_write(path,data):
    # Never overwrite an existing packet or recovery key. OS permissions restrict
    # access; the owner must also keep the recovery directory off synced storage.
    with os.fdopen(os.open(path,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600),'wb') as stream:stream.write(data)

def main():
    parser=argparse.ArgumentParser(description=__doc__);sub=parser.add_subparsers(dest='mode',required=True)
    writer=sub.add_parser('wrap');writer.add_argument('--public-key',required=True);writer.add_argument('--output',required=True)
    reader=sub.add_parser('unwrap');reader.add_argument('--packet',required=True);reader.add_argument('--private-key',required=True);reader.add_argument('--output',required=True)
    args=parser.parse_args()
    try:
        if args.mode=='wrap':
            packet=wrap(bytes.fromhex(os.environ['CRM_BACKUP_ENCRYPTION_KEY']),pathlib.Path(args.public_key).read_bytes());exclusive_write(args.output,(json.dumps(packet,indent=2)+'\n').encode())
            print('CRM_KEY_CUSTODY_WRAPPED '+json.dumps({'publicKeySha256':packet['publicKeySha256'],'backupKeySha256':packet['backupKeySha256']}))
        else:
            password=getpass.getpass('Local private-key passphrase: ').encode()
            private=serialization.load_pem_private_key(pathlib.Path(args.private_key).read_bytes(),password=password)
            key=unwrap(json.loads(pathlib.Path(args.packet).read_text()),private);exclusive_write(args.output,(key.hex()+'\n').encode());print('CRM_KEY_CUSTODY_RECOVERED: local file created')
    except Exception:raise SystemExit('CRM_KEY_CUSTODY_FAILED: verify key/packet and use a new output path') from None

if __name__=='__main__':main()
