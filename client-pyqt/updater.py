"""独立 Windows updater：再次验签、安全解压、原子 current 切换、健康检查与回滚。"""
from __future__ import annotations
import argparse, base64, hashlib, json, os, subprocess, sys, time, zipfile
from pathlib import Path
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey
from cryptography.hazmat.primitives.serialization import load_der_public_key

def report(event: str, error: str | None = None) -> None:
    path=Path(os.getenv("PDK_UPDATER_DECISION_FILE",""))
    try:
        import requests
        data=json.loads(path.read_text("utf-8"));artifact=data.get("artifact") or {}
        payload={"checkRequestId":data["checkRequestId"],"eventToken":data["eventToken"],"artifactId":artifact.get("artifactId"),
                 "eventType":event,"fromVersion":data.get("currentVersion"),"targetVersion":data.get("targetVersion"),"platform":"WINDOWS","errorCategory":error}
        requests.post(os.environ["PDK_UPDATER_API_BASE"].rstrip("/")+"/api/v1/client/updates/events",
                      headers={"X-PDK-App-ID":str(data["appId"]),"X-PDK-Device-ID":os.getenv("PDK_UPDATER_DEVICE_ID","")},json=payload,timeout=5)
    except Exception:pass
    if event=="INSTALL_SUCCEEDED" and path.is_file():path.unlink(missing_ok=True)

def fail(message: str, code: int = 2) -> None:
    print(f"[PDK-Updater] {message}", file=sys.stderr); raise SystemExit(code)

def verify(args: argparse.Namespace) -> None:
    package=Path(args.package)
    digest=hashlib.sha256()
    with package.open("rb") as source:
        for chunk in iter(lambda:source.read(1024*1024),b""):digest.update(chunk)
    if package.stat().st_size != args.file_size or digest.hexdigest()!=args.sha256: fail("包大小或摘要不一致")
    canonical="\n".join(["PDK-ARTIFACT-V1",str(args.app_id),args.version,args.platform,args.arch,args.package_type,str(args.file_size),args.sha256])
    key=load_der_public_key(base64.b64decode(args.public_key))
    if not isinstance(key,Ed25519PublicKey): fail("公钥不是 Ed25519")
    try:key.verify(base64.b64decode(args.signature),canonical.encode())
    except Exception:fail("构件签名验证失败")

def safe_extract(package: Path, target: Path, args: argparse.Namespace) -> dict:
    with zipfile.ZipFile(package) as z:
        names=z.namelist()
        if "update-manifest.json" not in names:fail("包清单缺失")
        manifest=json.loads(z.read("update-manifest.json"))
        if int(manifest.get("appId",0))!=args.app_id or manifest.get("version")!=args.version or manifest.get("platform")!=args.platform or manifest.get("arch")!=args.arch:fail("包清单目标不匹配")
        allowed=set(manifest.get("files") or [])|{"update-manifest.json"}
        build_config=manifest.get("buildConfig")
        if not build_config:fail("包清单必须声明 buildConfig")
        if build_config not in names:fail("包清单声明的 buildConfig 不存在")
        config=json.loads(z.read(build_config))
        if int(config.get("appId",0))!=args.app_id or config.get("version")!=args.version or config.get("entryPoint")!=args.entry_point:fail("内嵌构建配置与升级目标不一致")
        total=0
        for info in z.infolist():
            name=info.filename.replace("\\","/")
            if name.startswith("/") or "../" in name or (info.external_attr>>16)&0o170000==0o120000:fail("ZIP 含路径穿越或符号链接")
            if not info.is_dir() and name not in allowed:fail(f"ZIP 含未声明文件：{name}")
            total+=max(0,info.file_size)
            if total>4*1024**3:fail("解压体积超过限制")
        z.extractall(target)
        return manifest

def main() -> int:
    p=argparse.ArgumentParser();
    for name in ("package","install-root","version","entry-point","platform","arch","package-type","sha256","signature","public-key"):p.add_argument("--"+name,required=True)
    p.add_argument("--app-id",type=int,required=True);p.add_argument("--file-size",type=int,required=True);p.add_argument("--parent-pid",type=int,required=True)
    a=p.parse_args();verify(a)
    deadline=time.time()+60; parent_running=True
    while time.time()<deadline:
        try:os.kill(a.parent_pid,0);time.sleep(.5)
        except OSError:parent_running=False;break
    if parent_running:report("INSTALL_FAILED","MAIN_PROCESS_NOT_EXITED");fail("主程序未在 60 秒内退出，未执行安装")
    root=Path(a.install_root).resolve();versions=root/"versions";versions.mkdir(parents=True,exist_ok=True)
    stage=versions/(a.version+".staging");target=versions/a.version
    if stage.exists():import shutil;shutil.rmtree(stage)
    stage.mkdir();manifest=safe_extract(Path(a.package),stage,a)
    if target.exists():import shutil;shutil.rmtree(target)
    stage.replace(target)
    current=root/"current.json";previous=current.read_text("utf-8") if current.exists() else ""
    temp=root/"current.json.tmp";temp.write_text(json.dumps({"version":a.version,"path":str(target),"entryPoint":a.entry_point}),"utf-8");os.replace(temp,current)
    entry=target/a.entry_point
    if not entry.is_file():
        if previous:current.write_text(previous,"utf-8")
        fail("新版入口程序不存在")
    health=root/"update-health.ok";health.unlink(missing_ok=True);env=os.environ.copy();env["PDK_UPDATE_HEALTH_FILE"]=str(health)
    cmd=[sys.executable,str(entry)] if entry.suffix.lower()==".py" else [str(entry)]
    proc=subprocess.Popen(cmd,cwd=target,env=env,creationflags=getattr(subprocess,"CREATE_NO_WINDOW",0))
    deadline=time.time()+30
    while time.time()<deadline and proc.poll() is None and not health.exists():time.sleep(.5)
    if health.exists():report("INSTALL_SUCCEEDED");return 0
    if proc.poll() is None:proc.terminate()
    if previous:current.write_text(previous,"utf-8")
    else:current.unlink(missing_ok=True)
    report("INSTALL_FAILED","HEALTH_CHECK_FAILED");fail("新版启动自检失败，已回滚 current",3)
if __name__=="__main__":raise SystemExit(main())
