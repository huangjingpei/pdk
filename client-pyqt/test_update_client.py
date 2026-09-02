from __future__ import annotations
import base64, tempfile, unittest
from pathlib import Path
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat
from update_client import ClientUpdateManager, UpdateError

class FakeClient:
    base_url="http://localhost:8080"
    client_version=""

class UpdateClientTest(unittest.TestCase):
    def test_artifact_signature_is_bound_to_metadata(self):
        private=Ed25519PrivateKey.generate();public=base64.b64encode(private.public_key().public_bytes(Encoding.DER,PublicFormat.SubjectPublicKeyInfo)).decode()
        cfg={"version":"1.0.0","artifactPublicKeys":{"k1":public},"policyPublicKeys":{}}
        manager=ClientUpdateManager(FakeClient(),cfg,"D1")
        canonical="\n".join(["PDK-ARTIFACT-V1","3","1.1.0","WINDOWS","X64","ZIP","10","a"*64])
        signature=base64.b64encode(private.sign(canonical.encode())).decode()
        manager._verify(canonical,signature,"k1","artifact")
        with self.assertRaises(UpdateError):manager._verify(canonical.replace("X64","ARM64"),signature,"k1","artifact")

if __name__=="__main__":unittest.main()
