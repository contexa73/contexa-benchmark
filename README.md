# Contexa 공인 벤치마크 작업 폴더 (S 트랙)

계획: `E:/projects/contexa/docs/agent/에이전트-공방시험체계-고도화-기획안.md` 13절. 쇼케이스 데모와는 별개의 트랙입니다(데모의 사례·측정·화면은 바뀌지 않습니다).

## 구성

| 경로 | 내용 |
|---|---|
| `.venv` | 파이썬 3.12 가상환경(이 폴더 안에만 설치). 전체 목록은 `requirements.lock.txt` |
| `bench/` | 실행기. AgentDojo(`runner.py`), InjecAgent(`injecagent_runner.py`), 모델 어댑터(`agent_llm.py`), 가드 통신(`guards.py`), 가드를 끼우는 도구 실행 요소(`guarded_executor.py`) |
| `guard/` | Contexa 가드 앱(스프링 부트, 포트 19300). 도구 호출마다 Contexa 엔진이 판정 |
| `third_party/InjecAgent` | InjecAgent 공식 저장소(커밋 f19c9f2c). 공식 파일은 고치지 않음 |
| `runs/` | 실행 결과(AgentDojo 공식 로그, 요약 json, 가드 판정 기록 jsonl) |

## 고정한 버전과 이유

- `agentdojo==0.1.35`, 벤치마크 판 `v1.2.2`(공식 스크립트의 기본값).
- `anthropic==0.125.0`: 2.0부터 `MessageParam`이 클래스가 아니어서 AgentDojo가 메시지를 만들 때 실패합니다.
- 에이전트 모델 `claude-haiku-5-5`: API가 `temperature`를 받지 않아(400 "deprecated for this model") 어댑터(`bench/agent_llm.py`)가 그 인자 하나만 뺍니다. AgentDojo 파일은 고치지 않습니다.
- AgentDojo 공격이 모델을 부르는 이름은 모델 표(`MODEL_NAMES`)에서 가져오므로, 표에 없는 새 모델은 실행기에서 한 줄로 등록합니다("Claude").

## 실행

```
cd bench
# 방어 없음 기준선(공격 있음 / 없음)
../.venv/Scripts/python.exe runner.py --suite banking --attack important_instructions --defense none --logdir ../runs/<이름>
../.venv/Scripts/python.exe runner.py --suite banking --defense none --logdir ../runs/<이름>
# Contexa 켠 상태(가드 앱이 떠 있어야 함)
../.venv/Scripts/python.exe runner.py --suite banking --attack important_instructions --defense contexa --logdir ../runs/<이름>
# InjecAgent(첫 단계만)
../.venv/Scripts/python.exe injecagent_runner.py --setting base --defense none --out ../runs/<이름>
```

Anthropic 키는 `E:/projects/contexa/docs/ANTHROPIC_API_KEY.MD`에서 프로세스 안으로만 읽습니다(출력·저장 없음).

## 해석 라벨 (결과표에 항상 붙임)

- AgentDojo·InjecAgent에는 회사 업무 기록·평소 행동 기준선·위임 기록이 없습니다. 이 결과는 Contexa의 차별 요소(기록 대조·기준선)가 약한 "기록 없는 조건"의 결과입니다.
- 방어 없음 기준선과 Contexa 켠 상태는 같은 에이전트 모델·같은 하니스·같은 날 실행한 것만 비교합니다.
