@echo off
REM Benchmark Execution Script for Windows
REM Runs all benchmark classes and generates analysis

setlocal enabledelayedexpansion

echo ======================================
echo   JMH Benchmark Suite Runner
echo ======================================
echo.

REM Configuration
set JAR_FILE=target\benchmarks.jar
set OUTPUT_DIR=benchmark-results
set TIMESTAMP=%date:~-4%%date:~-10,2%%date:~-7,2%_%time:~0,2%%time:~3,2%%time:~6,2%
set TIMESTAMP=%TIMESTAMP: =0%

REM Check if JAR exists
if not exist "%JAR_FILE%" (
    echo [WARNING] Benchmark JAR not found. Building...
    call mvn clean package
    echo.
)

REM Create output directory
if not exist "%OUTPUT_DIR%" mkdir "%OUTPUT_DIR%"

echo [INFO] Starting benchmark suite at %date% %time%
echo.

echo ======================================
echo   Running Individual Benchmarks
echo ======================================
echo.

REM Function to run benchmarks - using labels as subroutines
call :run_benchmark "data_processing" "DataProcessingBenchmark"
call :run_benchmark "database" "DatabaseBenchmark"
call :run_benchmark "index_operations" "IndexOperationsBenchmark"
call :run_benchmark "search_service" "SearchServiceBenchmark"
call :run_benchmark "end_to_end" "EndToEndBenchmark"

REM Combine all results
echo ======================================
echo   Combining Results
echo ======================================
echo.

set COMBINED_FILE=%OUTPUT_DIR%\all_results_%TIMESTAMP%.csv

echo [INFO] Combining all CSV files...

REM Combine all CSV files (keep header from first file only)
set FIRST_FILE=1
for %%f in (%OUTPUT_DIR%\*_%TIMESTAMP%.csv) do (
    if !FIRST_FILE!==1 (
        type "%%f" > "%COMBINED_FILE%"
        set FIRST_FILE=0
    ) else (
        more +1 "%%f" >> "%COMBINED_FILE%"
    )
)

echo [SUCCESS] Combined results saved to: %COMBINED_FILE%
echo.

REM Create copy as latest results (Windows doesn't support symlinks easily)
copy /Y "%COMBINED_FILE%" "%OUTPUT_DIR%\latest_results.csv" >nul
echo [SUCCESS] Latest results copied to: %OUTPUT_DIR%\latest_results.csv
echo.

REM Run Python analysis if available
echo ======================================
echo   Analyzing Results
echo ======================================
echo.

where python >nul 2>nul
if %errorlevel%==0 (
    if exist "analyze_benchmarks.py" (
        echo [INFO] Running Python analysis...
        python analyze_benchmarks.py "%COMBINED_FILE%"
        echo.
    ) else (
        echo [WARNING] analyze_benchmarks.py not found, skipping analysis
        echo.
    )
) else (
    echo [WARNING] Python not found, skipping analysis
    echo   Install Python 3 and dependencies to enable automatic analysis:
    echo   pip install pandas matplotlib seaborn numpy
    echo.
)

REM Summary
echo ======================================
echo   Benchmark Suite Complete!
echo ======================================
echo.
echo [SUCCESS] All benchmarks completed successfully
echo.
echo  Results location:
echo    %OUTPUT_DIR%\
echo.
echo  Generated files:
dir /B %OUTPUT_DIR%\*_%TIMESTAMP%.csv 2>nul
echo.
echo  Combined results:
echo    %COMBINED_FILE%
echo.

if exist "%OUTPUT_DIR%\benchmark-results" (
    echo Generated visualizations:
    dir /B %OUTPUT_DIR%\benchmark-results\*.png 2>nul
    echo.
)

echo Next steps:
echo    1. Review results in: %OUTPUT_DIR%\
echo    2. Check visualizations in: %OUTPUT_DIR%\benchmark-results\
echo    3. Analyze summary statistics: %OUTPUT_DIR%\summary_statistics.csv
echo    4. Include findings in your Stage 2 report
echo.
echo Finished at %date% %time%
echo ======================================

goto :eof

REM Subroutine to run a benchmark
:run_benchmark
set name=%~1
set class=%~2
set output_file=%OUTPUT_DIR%\%name%_%TIMESTAMP%.csv

echo [INFO] Running: %name%
echo   Class: %class%
echo   Output: %output_file%

java -jar "%JAR_FILE%" "%class%" -rf csv -rff "%output_file%" -t 4 -f 1

if %errorlevel%==0 (
    echo   [SUCCESS] Completed
) else (
    echo   [ERROR] Failed with error code %errorlevel%
)
echo.
goto :eof