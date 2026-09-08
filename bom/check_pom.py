"""After :bom:generatePomFileForMavenPublication, validate a Maven consumer offline."""

from pathlib import Path
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET

pom = Path(__file__).parent / "build/publications/maven/pom-default.xml"
model = ET.parse(pom).getroot()
ns = {"m": "http://maven.apache.org/POM/4.0.0"}
group = model.findtext("m:groupId", namespaces=ns)
version = model.findtext("m:version", namespaces=ns)
classifiers = (
    "linux-x86_64-gnu", "linux-aarch64-gnu",
    "linux-x86_64-musl", "linux-aarch64-musl",
    "macos-aarch64", "windows-x86_64",
)

with tempfile.TemporaryDirectory() as directory:
    root = Path(directory)
    repo = root / "repository"
    installed = repo / group.replace(".", "/") / "bom" / version / f"bom-{version}.pom"
    installed.parent.mkdir(parents=True)
    shutil.copyfile(pom, installed)
    dependencies = "".join(
        f"<dependency><groupId>{group}</groupId><artifactId>core-bridge</artifactId>"
        f"<classifier>{classifier}</classifier></dependency>"
        for classifier in classifiers
    )
    consumer = root / "pom.xml"
    consumer.write_text(
        '<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>'
        '<groupId>test</groupId><artifactId>bom-consumer</artifactId><version>1</version>'
        '<dependencyManagement><dependencies><dependency>'
        f'<groupId>{group}</groupId><artifactId>bom</artifactId><version>{version}</version>'
        '<type>pom</type><scope>import</scope></dependency></dependencies></dependencyManagement>'
        f'<dependencies>{dependencies}</dependencies></project>'
    )
    subprocess.run(
        ["mvn", "--offline", "--quiet", f"-Dmaven.repo.local={repo}", "-f", str(consumer), "validate"],
        check=True,
    )
