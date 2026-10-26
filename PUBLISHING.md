# 배포 가이드

세 패키지는 모두 버전 `0.1.0`으로 준비되어 있고, 로컬에서 아래 아티팩트가 빌드·검증됩니다.

| 레지스트리 | 좌표 | 로컬 빌드 |
|-----------|------|-----------|
| npm | `kakaotalk-bot` | `cd nodejs && npm pack` → `kakaotalk-bot-0.1.0.tgz` |
| PyPI | `kakaotalk-bot` | `cd python && python -m build` → `dist/*.whl`, `dist/*.tar.gz` (`twine check` 통과) |
| Maven Central | `io.github.punch5545:kakaotalk-bot` | `cd java && mvn package` → `target/*.jar` (+ sources, javadoc) |

배포 자체는 계정 인증이 필요하므로 아래 두 방법 중 하나로 진행합니다.
**권장은 GitHub Actions**입니다. npm과 PyPI는 Trusted Publishing(OIDC)으로 토큰 없이 배포되고,
Maven Central만 시크릿 4개가 필요합니다.

## 방법 A. GitHub Actions로 배포 (권장)

`.github/workflows/publish.yml`이 `v*` 태그 푸시 시 세 레지스트리에 동시에 배포합니다.
Actions 탭의 **Run workflow**로 레지스트리를 하나씩 골라 실행할 수도 있습니다.

### 1회 설정

**npm**
1. https://www.npmjs.com 에 로그인합니다 (계정이 없으면 생성).
2. 첫 배포는 Trusted Publisher 등록 전에 패키지가 존재해야 하므로, 아래 둘 중 하나를 택합니다.
   - 로컬에서 `cd nodejs && npm login && npm publish --access public`로 0.1.0을 먼저 올린다, 또는
   - 레포지토리 Settings → Environments → `npm` 환경을 만들고, 시크릿 `NPM_TOKEN`(Granular Access Token, publish 권한)을 추가한 뒤
     `publish.yml`의 npm 잡 `npm publish` 단계에 `env: NODE_AUTH_TOKEN: ${{ secrets.NPM_TOKEN }}`을 넣어 1회 실행한다.
3. 이후 npmjs.com → 패키지 → Settings → **Trusted Publisher** 에서
   GitHub Actions / `punch5545/KakaoTalk-Bot-Wrapper` / workflow `publish.yml` / environment `npm` 을 등록하면 토큰이 필요 없습니다.

**PyPI**
1. https://pypi.org 에 로그인합니다 (2FA 필수).
2. Account settings → **Publishing** → *Add a new pending publisher*:
   - PyPI project name: `kakaotalk-bot`
   - Owner: `punch5545`, Repository: `KakaoTalk-Bot-Wrapper`
   - Workflow name: `publish.yml`, Environment: `pypi`
3. 레포지토리 Settings → Environments 에 `pypi` 환경을 만듭니다 (시크릿 불필요).

**Maven Central**
1. https://central.sonatype.com 에 GitHub 계정으로 로그인하면 네임스페이스 `io.github.punch5545`가 자동 검증됩니다.
2. 우측 상단 계정 → **Generate User Token** 으로 username/password 토큰을 발급합니다.
3. GPG 서명 키를 만듭니다 (Homebrew: `brew install gnupg`).

   ```bash
   gpg --full-generate-key            # RSA 4096, 이름/이메일 입력
   gpg --list-secret-keys --keyid-format long
   gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>
   gpg --armor --export-secret-keys <KEY_ID> > private.asc   # 시크릿 등록 후 파일 삭제
   ```

4. 레포지토리 Settings → Environments → `maven-central` 환경을 만들고 시크릿 4개를 추가합니다.
   `CENTRAL_USERNAME`, `CENTRAL_PASSWORD`(2번 토큰), `GPG_PRIVATE_KEY`(private.asc 내용), `GPG_PASSPHRASE`.

### 배포 실행

```bash
git tag v0.1.0 && git push origin v0.1.0
```

세 잡이 끝나면 pypi.org / central.sonatype.com 에서 패키지가 보입니다 (Central은 반영에 수십 분 걸릴 수 있습니다).

**npm은 한 단계가 더 있습니다.** npm은 2026년부터 CI에서 올린 버전을 바로 공개하지 않고
*stage 대기열*에 넣습니다(staged publishing). 워크플로가 성공해도 레지스트리에는
`0.0.0-stage` 자리표시자만 보이고, 실제 버전은 메인테이너가 2FA로 승인해야 공개됩니다.

- 웹: npmjs.com → 패키지 페이지 → **Staged** 탭에서 해당 버전을 Approve
- CLI (npm 11.15+): `npm stage list kakaotalk-bot` 으로 stage id 확인 후 `npm stage approve <stage-id>`

승인 뒤 `latest` 태그가 실제 버전을 가리키는지 `npm view kakaotalk-bot dist-tags`로 확인합니다.
`0.0.0-stage`는 npm이 만든 prerelease 자리표시자라 설치 범위에 잡히지 않으며 그대로 두어도 됩니다.

## 방법 B. 로컬에서 직접 배포

**npm**

```bash
cd nodejs && npm login && npm publish --access public
```

**PyPI** — pypi.org에서 API 토큰을 만든 뒤:

```bash
cd python && python -m build && python -m twine upload dist/*
```

(username `__token__`, password에 토큰 입력)

**Maven Central** — `~/.m2/settings.xml`에 Central Portal 토큰을 넣고:

```xml
<settings>
  <servers>
    <server>
      <id>central</id>
      <username>CENTRAL_USERNAME</username>
      <password>CENTRAL_PASSWORD</password>
    </server>
  </servers>
</settings>
```

```bash
cd java && mvn -P release deploy
```

GPG 키가 로컬에 있어야 하며 `-Dgpg.passphrase=...` 또는 gpg-agent로 패스프레이즈를 전달합니다.

## 다음 버전 올리기

세 파일의 버전을 함께 올리고 태그를 푸시합니다.

- `nodejs/package.json` → `"version"`
- `python/pyproject.toml` → `version`, `python/kakaotalk_bot/__init__.py` → `__version__`
- `java/pom.xml` → `<version>`
