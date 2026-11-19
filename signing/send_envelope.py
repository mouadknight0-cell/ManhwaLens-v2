import base64, json, os, subprocess, sys, time
from pathlib import Path
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
run=sys.argv[1]
assert run.isdigit()
metadata=json.loads(subprocess.check_output(['gh','api','repos/mouadknight0-cell/ManhwaLens-v2/actions/runs/'+run]))
assert metadata['path']=='.github/workflows/sign-release.yml', 'Unexpected workflow'
assert metadata['status']=='in_progress', 'Signer must be active'
path='repos/mouadknight0-cell/ManhwaLens-v2/issues/4/comments'
request=None
for _ in range(36):
    comments=json.loads(subprocess.check_output(['gh','api',path]))
    for comment in comments:
        if comment['user']['login']=='github-actions[bot]' and comment['body'].startswith('SIGNING_REQUEST '+run+'\n'):
            request=comment['body']; break
    if request: break
    time.sleep(5)
assert request, 'No authenticated signing request'
public=serialization.load_pem_public_key(request.split('```\n')[1].split('```')[0].encode())
key=AESGCM.generate_key(bit_length=256)
nonce=os.urandom(12)
material=json.dumps({'keystore':base64.b64encode(Path(os.environ['SIGNING_KEYSTORE_FILE']).read_bytes()).decode(),'password':Path(os.environ['SIGNING_PASSWORD_FILE']).read_text().strip()}).encode()
b64=lambda b:base64.b64encode(b).decode()
envelope={'wrapped':b64(public.encrypt(key,padding.OAEP(mgf=padding.MGF1(hashes.SHA256()),algorithm=hashes.SHA256(),label=None))), 'nonce':b64(nonce),'ciphertext':b64(AESGCM(key).encrypt(nonce,material,run.encode()))}
payload=json.dumps({'body':'SIGNING_ENVELOPE '+run+'\n'+json.dumps(envelope)})
subprocess.run(['gh','api',path,'--input','-','--jq','.id'], input=payload,text=True,check=True)
print('Encrypted envelope delivered; no plaintext credentials transmitted in comments.')
