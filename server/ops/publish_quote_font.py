"""Publish the one fixed, publicly licensed PDF font asset. No business/DB access."""
import hashlib,io,json,os,urllib.request
import boto3
from botocore.config import Config
from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont

SOURCE='https://raw.githubusercontent.com/notofonts/noto-cjk/f8d157532fbfaeda587e826d4cd5b21a49186f7c/Sans/Variable/TTF/Subset/NotoSansSC-VF.ttf'
SOURCE_SHA='d68bafcb48a2707749396aa12bbbd833cb70401f3a9a689fd2902c7e0d295964'
FONT_SHA='12a7fef9f024f411ed7e2d89a614b9865f14ae4129ad4993a7e117f36322a794'
def main():
    if os.environ.get('CRM_ASSET_PUBLISH_SCOPE')!='p2a-font-only':raise RuntimeError('FONT_ONLY_SCOPE_REQUIRED')
    source=urllib.request.urlopen(SOURCE,timeout=60).read()
    if hashlib.sha256(source).hexdigest()!=SOURCE_SHA:raise RuntimeError('SOURCE_FONT_HASH_MISMATCH')
    font=TTFont(io.BytesIO(source),recalcTimestamp=False);instantiateVariableFont(font,{'wght':400},inplace=True);stream=io.BytesIO();font.save(stream);data=stream.getvalue()
    if hashlib.sha256(data).hexdigest()!=FONT_SHA:raise RuntimeError('STATIC_FONT_HASH_MISMATCH')
    endpoint=os.environ['CRM_S3_ENDPOINT']
    if not endpoint.startswith('https://'):raise RuntimeError('PRIVATE_HTTPS_STORAGE_REQUIRED')
    s3=boto3.client('s3',endpoint_url=endpoint,region_name=os.environ.get('CRM_S3_REGION','auto'),aws_access_key_id=os.environ['CRM_S3_ACCESS_KEY'],aws_secret_access_key=os.environ['CRM_S3_SECRET_KEY'],config=Config(s3={'addressing_style':'virtual'},connect_timeout=10,read_timeout=60))
    key='system/fonts/noto-sans-sc-'+FONT_SHA+'.ttf';bucket=os.environ['CRM_S3_BUCKET']
    s3.put_object(Bucket=bucket,Key=key,Body=data,ContentType='font/ttf',Metadata={'sha256':FONT_SHA,'license':'SIL-OFL-1.1'})
    actual=s3.get_object(Bucket=bucket,Key=key)['Body'].read()
    if hashlib.sha256(actual).hexdigest()!=FONT_SHA:raise RuntimeError('FONT_READBACK_MISMATCH')
    print('CRM_QUOTE_FONT_RESULT '+json.dumps({'sha256':FONT_SHA,'bytes':len(data),'readbackVerified':True,'businessObjectsTouched':False}))
if __name__=='__main__':
    try:main()
    except Exception:raise RuntimeError('QUOTE_FONT_PUBLICATION_FAILED') from None
