@echo off
chcp 65001 >nul
echo ====================================================================
echo   MaxSync Pro Mobile - 스토어 심사 없는 직접 설치용(.APK) 자동 빌드
echo ====================================================================
echo.
if exist gradlew.bat (
    call gradlew.bat assembleRelease
) else (
    gradle assembleRelease
)
echo.
echo [완료] app\build\outputs\apk\release\ 폴더 안에서 .apk 파일을 확인하세요!
pause