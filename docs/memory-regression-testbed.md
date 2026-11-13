# 실제 Android 기기용 CloudStream 메모리 종합 진단

이 문서는 **사용자의 실제 태블릿/휴대전화에 설치된 CloudStream에서 직접 수행하는 테스트**만 다룹니다. CloudStream의 로그캣 저장과 Elevate 설정의 프로파일 시작·종료 기능으로 증거를 수집합니다. 별도의 ADB 명령은 필요하지 않습니다. 테스트를 자동으로 수행하는 기능이 아니라, 플러그인에 내장된 계측과 CloudStream 로그캣·Perfetto를 묶어 검증하는 **실기기 테스트 절차 및 판정 기준**입니다.

## 사용 방법

1. 변경된 Elevate, StremioC, SyncCS를 기기에 설치합니다.
2. CloudStream 홈 화면을 잠시 안정화한 뒤, 평소와 동일하게 영상을 열고/전환하고/종료하거나 포스터 목록을 스크롤합니다.
3. **CloudStream의 일반 로그캣을 저장**합니다. `StremioSPTrace`, `ElevateMedia3Trace`, `ElevateCoilTrace`, `ElevateJavaAlloc`은 INFO 로그를 사용하므로 별도 ADB 설정이 필요하지 않습니다. (플러그인 로드·관련 기능 사용이 전제.)
4. **누적 Java 할당 스택**까지 비교하려면 Elevate 설정에서 기존 **Start Java allocation profile**을 눌러 해당 동작을 재현하고 Stop 후 `.perfetto-trace`를 저장합니다. **로그캣만 저장해도 진단 이벤트/메모리 스냅샷은 보이지만 Perfetto 바이너리 자체는 포함되지 않습니다.**
5. 분석 시 각 테스트에 앱/플러그인 버전, 재현 동작, 로그캣, Perfetto 파일(사용한 경우)을 대응시킵니다.

## 실제 기기 테스트 순서

| 순서 | 사용자가 할 동작 | 로그캣 확인 | 판정 기준 |
| --- | --- | --- | --- |
| 0. 시작 기준점 | 홈에서 20초간 대기 후 Java allocation profile Start | `ElevateJavaAlloc HEAP phase=PROFILE_START` | Java 사용 힙·Native 할당 힙·PSS 기록 |
| 1. StreamPlay 요청 | 같은 작품·회차의 소스 검색, 재진입, 빠른 재조회 | `StremioSPTrace BEGIN/END`, `identicalActiveBefore` | 동일 제공자 객체·전체 payload·casting 값이 같은 실행이 실제로 겹쳤을 때만 중복 후보 |
| 2. Media3 MKV 재생 | 영상 재생 → 다른 소스로 전환 → 재생 종료 → 같은 과정을 3회 반복 | `ElevateMedia3Trace PLAYER_BIND/PLAYER_STATE/VIEW_DESTROY/ACTIVITY_STOP/HEAP`, `CS3ExoPlayer` | 플레이어 ID·소스 전환·최종 종료 흐름 대조, Activity 중단 5초 뒤의 Java/Native/PSS 비교 |
| 3. Media3 PiP | 재생 → PiP → 복귀 → 최종 종료 | 동일한 Media3 로그 | PiP 중 의도된 플레이어 유지는 정상; 최종 종료 후 해제 기록과 메모리 스냅샷 비교 |
| 4. Coil 포스터 | 배우창 → 추천목록 → 라이브러리 → 홈에서 포스터 스크롤·되돌아오기 | `ElevateCoilTrace PREFETCH/DISPLAY/CACHE_PROBE_HIT/LOAD_RESULT/SHARED_INFLIGHT` | 동일 URL의 크기·Scale·캐시 출처 비교; 불필요한 재디코딩 의심 시 Perfetto `StaticImageDecoder.decode`와 대조 |
| 5. 종료 비교 | 테스트 종료 후 홈으로 복귀, 프로파일 Stop | `ElevateJavaAlloc HEAP phase=PROFILE_STOP/PROFILE_RESULT`, `ElevateMedia3Trace HEAP phase=AFTER_EXIT_5S` | 시작 상태와 종료 후 Java 사용 힙·Native 힙·PSS 비교, 비정상적인 반복 증가 여부 검토 |

프로파일은 요청 기준 최대 90초간 수집하므로, 긴 테스트를 한 번에 녹화하지 말고 **시나리오별로 별도 시작·종료**하는 편이 원인 구분에 유리합니다. Perfetto 파일의 버퍼 초과·연결 끊김·클라이언트 오류가 있으면 해당 수집은 불완전한 자료입니다.

## SyncCS v3 신규 백업·P0~P4 검증

**기존 v2를 읽거나 마이그레이션하지 않습니다.** 기존 GitHub v2 백업이 있으면
모든 기기를 동시에 기존 백업에 연결한 상태로 방치하지 말고, 먼저 **원본 A 기기**
에서 SyncCS 계정에 다시 연결합니다. 구형 v2 발견 시 표시되는
`Replace legacy backup with v3?` 확인창에서 현재 A의 설정을 새 기준으로
지정합니다. 이 동작은 기존 Git 커밋 이력을 강제 삭제하지 않으며,
**v3 최신 스냅샷을 새로운 Git CAS 커밋으로 생성**합니다.
B 기기는 A의 신규 v3 동기화가 `Synced:`로 끝난 다음 연결해 전체 복원합니다.
새 v3 백업이 생성되기 전에는 다른 기기의 자동 동기화를 재개하지 않습니다.

| 순서 | 사용자 동작 | 로그·결과 판정 |
| --- | --- | --- |
| 1. 신규 원본 A | 구형 v2 교체 승인 → 최초 전체 동기화 | v3 백업 생성, `BASE_READ_SQLITE initialized=false full=true`, `BASE_WRITE_SQLITE` |
| 2. 신규 기기 B | 동일 GitHub 계정에 신규 연결 → 전체 복원 | A와 동일한 설정·프로필·플러그인·라이브러리 |
| 3. 변경 없음 | A에서 Sync now 재실행 | 새 Git commit 없이 `Synced:` |
| 4. 일반 설정 | Java allocation profile 시작 → 설정 1개 변경 → 동기화 → 종료 | `BASE_READ_SQLITE selected=true`, `V3_OVERLAY`, `BASE_WRITE_SQLITE` |
| 5. 변경 2개 | 독립 키 2개 변경 후 동일 측정 | 변경된 버킷만 업데이트, GitHub API 호출 및 할당량 |
| 6. 삭제 | 목록 항목 제거 후 B에서 확인 | 명시적 삭제만 전파되고 tombstone 반영 |
| 7. 전체 감사 | 1시간 정기 감사 또는 재시작 후 동기화 | 전체 기준 검증 유지, 신규 누락 항목 검출 |
| 8. 충돌·복구 | A/B 교차 편집, 기기 종료 후 복구 | CAS 충돌 시 기존 원격 데이터 유지, 실패 시 로컬 저널 복구 |

`SyncCSAlloc V3_OVERLAY`의 `changedShards`, `compacted`,
`writeBytes`를 기록합니다. 설정 하나 변경에서 기존 대형 기본 샤드를
다시 저장하지 않고 소형 오버레이를 갱신하는지 확인합니다.
`BASE_READ_SQLITE selected=true`일 때 키가 수만 개가 아니라
실제 관찰 대상에 국한되는지 확인합니다. 메모리 측정 대상은 **최초 전체
복원이 아닌, 이후의 1~2개 변경 증분 동기화**입니다.

자동화 테스트는 `GithubStoreIntegrationTest`의 100회 이상 커밋 비교,
오버레이 압축·삭제·변조·rollback·CAS 테스트와
`SyncDomainRegressionTest`의 부분 DB 읽기·쓰기를 포함합니다.
실기기 검증 결과를 확인하기 전에는 Java 할당량 개선율이나 기능 무손상을
확정하지 않습니다.

## SyncCS SQLite 증분 기준 데이터 검증

- 기존 로컬 `base`·`remote_versions` JSON은 폐기하며 **읽기·마이그레이션을 수행하지 않습니다**. GitHub v2도 신규 v3로 변환하지 않습니다. 사용자의 승인 후 원본 A에서 신규 v3 백업을 생성하고 B에서 전체 복원합니다.
- 일반 설정 하나 변경 및 목록 항목 변경 테스트에서는 로그캣의 `SyncCSAlloc BASE_READ_SQLITE`/`BASE_WRITE_SQLITE`에서 `hashUpserts`, `hashDeletes`, `versionUpserts`, `versionDeletes`를 비교합니다. 변경된 행만 기록되는지 확인하고, Perfetto에서 `persistCommittedBaseline()` Java 할당량을 기존 약 54MiB/회 기준과 비교합니다.
- 최초 전체 동기화 후에는 동일한 GitHub revision에서 HEAD-only 빠른 종료가 정상 작동해야 합니다. 다른 기기 수정, 원격 복구 generation 변경, SQLite/SharedPreferences revision 불일치가 발생하면 기존 증분 상태를 신뢰하지 않고 인증된 전체 조회 경로로 전환합니다.
- 기준 데이터 해시·버전과 GitHub revision은 SQLite의 동일 트랜잭션으로 기록하며, 로컬 설정 복원용 `LocalSyncJournal`에는 SQLite 스냅샷도 포함됩니다. 실제 앱 종료/장애 복구와 A/B 변경·삭제는 별도 실기기 확인이 필요합니다.

## SyncCS 실기기 확인

- **기기 1대에서 수행 가능:** 동일 설정 연속 동기화, 목록 추가·삭제, 변경 후 재동기화, GitHub 연결 일시 오류 후 재시도. 테스트 전 설정 백업을 확보합니다.
- **기기 2대가 있어야 완전 검증 가능:** A/B 동시 편집, 삭제 충돌 및 3-way 병합, 새 기기 최초 복원, 프로필 변경 후 상대 기기의 재동기화. 기기 하나만 사용했다면 해당 항목을 **NOT_TESTED**로 남깁니다.
- **코드 단위 검증은 별도:** `SyncCS/src/test/.../SyncDomainRegressionTest.kt`의 해시 동등성·기준 데이터 비교와 기존 회귀 테스트는 정적/개발 환경 검증이며, 실제 기기 양방향 동기화 성공을 대체하지 않습니다.

## 메모리 수치 해석

- `javaUsedKiB`: 스냅샷 시점의 Java 힙 사용량. `nativeAllocatedKiB`: Native 힙 할당량. `totalPssKiB`: 프로세스 PSS.
- 이 값은 **강제 GC 없이** 읽으므로 스냅샷 1~2개의 증가만으로 누수를 판정하지 않습니다. 같은 화면 상태에서 3회 이상 비교하고, 필요하면 별도 retained-heap/HPROF 분석을 수행합니다.
- 90초는 Android에 요청하는 수집 시간입니다. Android가 지원 범위에 따라 기간을 조정할 수 있으므로 Perfetto의 실제 타임스탬프를 확인합니다.
- `ElevateJavaAlloc`의 `.perfetto-trace`는 **누적 할당 호출 스택**입니다. 사용 중인 힙의 누수/잔존 객체를 직접 입증하는 HPROF 덤프가 아닙니다.
- `ElevateMedia3Trace VIEW_DESTROY`는 Fragment 화면 제거 이벤트입니다. `DownloadedPlayerActivity`의 뒤로가기는 Activity를 파괴하지 않고 백그라운드로 보낼 수 있으므로 `ACTIVITY_STOP` 및 `AFTER_ACTIVITY_STOP_5S`도 수집합니다. 실제 `ExoPlayer.release()` 호출 여부는 코어의 `CS3ExoPlayer` 로그로 별도 확인합니다.
- Coil의 `LOAD_RESULT source=DISK`만으로 중복 디코딩이 확정되는 것은 아닙니다. 같은 URL과 서로 다른 요청 크기를 구분하고 Perfetto의 실제 디코더 할당 스택과 교차 확인합니다.
- 로그의 URL은 원문 대신 간단한 해시로 기록합니다. 같은 해시가 동일한 URL임을 수학적으로 보장하지는 않습니다.

## 결과 보고서

각 시나리오를 `PASS`, `FAIL`, `NOT_TESTED`로 구분합니다. **기기에서 실제 실행하지 않은 경우 PASS로 처리하지 않습니다.** 증거로 CloudStream 로그캣과 필요한 경우 Perfetto 파일을 첨부합니다. 원인이 확인되면 변경 지점만 수정하고 진단용 임시 로깅은 추후 제거합니다.
