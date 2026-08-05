#!/bin/bash
# ==============================================================
# run_all_scenarios.sh — Batch runner per tutti e 6 gli scenari
# Progetto Network Security — Bubble Rap + Sybil/Buffer Overflow
# ==============================================================
#
# Uso:
#   chmod +x run_all_scenarios.sh
#   ./run_all_scenarios.sh                    # Esegue tutti i 6 scenari
#   ./run_all_scenarios.sh --benchmark        # Esegue + benchmark CPU/RAM con psrecord
#   ./run_all_scenarios.sh --run 0 2 5        # Esegue solo i run specificati
#
# Prerequisiti:
#   - Java 11 (OpenJDK)
#   - The ONE compilato (./compile.sh già eseguito)
#   - psrecord (pip3 install psrecord) se si usa --benchmark
#

set -euo pipefail

# ─── Configurazione ──────────────────────────────────────────
SETTINGS_FILE="bubble_rap_settings.txt"
RESULTS_DIR="results"
BENCHMARK=false
RUNS_TO_EXECUTE=(0 1 2 3 4 5)

SCENARIO_NAMES=(
    "S1_BR_Baseline"
    "S2_BR_Attack_NoMitigation"
    "S3_BR_Attack_ReputationMitigation"
    "S4_BRI_Baseline"
    "S5_BRI_Attack_NoMitigation"
    "S6_BRI_Attack_InterestMitigation"
)

# ─── Parsing argomenti ───────────────────────────────────────
while [[ $# -gt 0 ]]; do
    case "$1" in
        --benchmark)
            BENCHMARK=true
            shift
            ;;
        --run)
            shift
            RUNS_TO_EXECUTE=()
            while [[ $# -gt 0 && "$1" =~ ^[0-9]+$ ]]; do
                RUNS_TO_EXECUTE+=("$1")
                shift
            done
            ;;
        *)
            echo "Uso: $0 [--benchmark] [--run 0 1 2 ...]"
            exit 1
            ;;
    esac
done

# ─── Verifica prerequisiti ───────────────────────────────────
if [ ! -f "one.sh" ]; then
    echo "ERRORE: one.sh non trovato. Eseguire dallo root di the-one/"
    exit 1
fi

if [ ! -d "target" ]; then
    echo "ATTENZIONE: target/ non trovato. Compilo il progetto..."
    ./compile.sh
fi

# Crea directory risultati
mkdir -p "$RESULTS_DIR"
mkdir -p reports

# ─── Funzione di esecuzione singolo scenario ─────────────────
run_scenario() {
    local run_index=$1
    local scenario_name="${SCENARIO_NAMES[$run_index]}"
    local log_file="$RESULTS_DIR/${scenario_name}.log"
    local benchmark_file="$RESULTS_DIR/${scenario_name}_benchmark.txt"
    local benchmark_plot="$RESULTS_DIR/${scenario_name}_benchmark.png"

    echo ""
    echo "╔══════════════════════════════════════════════════════════════╗"
    echo "║  Run $run_index: $scenario_name"
    echo "╚══════════════════════════════════════════════════════════════╝"
    echo "  Log: $log_file"
    echo "  Inizio: $(date '+%Y-%m-%d %H:%M:%S')"
    echo ""

    local start_time=$(date +%s)

    if [ "$BENCHMARK" = true ]; then
        # Avvia il simulatore in background e monitora con psrecord
        java -Xmx512M -cp target:lib/ECLA.jar:lib/DTNConsoleConnection.jar \
            core.DTNSim -b "$run_index" "$SETTINGS_FILE" > "$log_file" 2>&1 &
        local sim_pid=$!

        # Monitoraggio con psrecord
        psrecord "$sim_pid" \
            --log "$benchmark_file" \
            --plot "$benchmark_plot" \
            --include-children \
            --interval 1 2>/dev/null || true

        wait "$sim_pid" || true
    else
        # Esecuzione standard senza benchmark
        ./one.sh -b "$run_index" "$SETTINGS_FILE" > "$log_file" 2>&1
    fi

    local end_time=$(date +%s)
    local duration=$((end_time - start_time))

    echo "  Fine: $(date '+%Y-%m-%d %H:%M:%S') (durata: ${duration}s)"

    # Copia i report generati nella directory risultati
    for report_file in reports/*"${SCENARIO_NAMES[$run_index]}"*; do
        if [ -f "$report_file" ]; then
            cp "$report_file" "$RESULTS_DIR/"
        fi
    done
    
    # Copia anche report generici (sovrascrivendo ad ogni run)
    for report_file in reports/*.txt; do
        if [ -f "$report_file" ]; then
            local base_name=$(basename "$report_file")
            cp "$report_file" "$RESULTS_DIR/${scenario_name}_${base_name}"
        fi
    done

    echo "  Report copiati in $RESULTS_DIR/"
}

# ─── Main: Esecuzione di tutti gli scenari ───────────────────
echo "╔══════════════════════════════════════════════════════════════╗"
echo "║  BUBBLE RAP — SYBIL+BUFFER OVERFLOW SIMULATION BATCH       ║"
echo "║  Scenari: ${RUNS_TO_EXECUTE[*]}                                        "
echo "║  Benchmark: $BENCHMARK                                     "
echo "╚══════════════════════════════════════════════════════════════╝"

total_start=$(date +%s)

for run_index in "${RUNS_TO_EXECUTE[@]}"; do
    if [ "$run_index" -ge 0 ] && [ "$run_index" -le 5 ]; then
        run_scenario "$run_index"
    else
        echo "ATTENZIONE: Run index $run_index non valido (0-5). Saltato."
    fi
done

total_end=$(date +%s)
total_duration=$((total_end - total_start))

echo ""
echo "╔══════════════════════════════════════════════════════════════╗"
echo "║  COMPLETATO — Tutti gli scenari eseguiti                    ║"
echo "║  Durata totale: ${total_duration}s                                      "
echo "║  Risultati in: $RESULTS_DIR/                                "
echo "╚══════════════════════════════════════════════════════════════╝"
echo ""
echo "Prossimo passo: analizza i risultati con lo script Python"
echo "  python3 analyze_results.py $RESULTS_DIR"
