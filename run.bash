#!/usr/bin/env bash
function die() {
  echo $*
  exit 1
}

# Show help if requested
if [[ "$1" == "--help" || "$1" == "-h" || "$1" == "help" ]]; then
    echo "LittleProxy Run Script"
    echo "Usage: $0 [options]"
    echo ""
    echo "Options:"
    echo "  --server                    Run as server"
    echo "  --config <file>             Configuration file path"
    echo "  --port <port>               Port to listen on"
    echo "  --log_config <file>         Log4j2 configuration file path"
    echo "  --activity_log_format <fmt>  Activity log format (CLF, JSON, etc.)"
    echo "  --async_logging_default     Use asynchronous logging with default config"
    echo "  --activity_log_request_prefix_headers <csv>   Prefixes of request headers to log"
    echo "  --activity_log_request_regex_headers <csv>    Regex patterns of request headers to log"
    echo "  --activity_log_request_exclude_headers <csv>  Regex patterns of request headers to exclude"
    echo "  --activity_log_response_prefix_headers <csv>  Prefixes of response headers to log"
    echo "  --activity_log_response_regex_headers <csv>   Regex patterns of response headers to log"
    echo "  --activity_log_response_exclude_headers <csv> Regex patterns of response headers to exclude"
    echo "  --help, -h, help            Show this help message"
    echo ""
    echo "Example:"
    echo "  $0 --server --config ./config/littleproxy.properties --port 9092 --async_logging_default"
    exit 0
fi

mvn package -Dmaven.test.skip=true || die "Could not package"

fullPath=`dirname $0`
jar=`find $fullPath/target/littleproxy*-littleproxy-shade.jar`
cp=`echo $jar | sed 's,./,'$fullPath'/,'`

# Initialize Java arguments
# Build java argument array so glob patterns (e.g. regex headers) are not re-expanded by the shell
javaArgs=(-server -XX:+HeapDumpOnOutOfMemoryError -Xmx800m)

# Parse arguments to handle --async_logging_default flag and build proper argument list
async_logging_default=false
remaining_args=()
log_config_set=false
custom_log_config=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        --async_logging_default)
            async_logging_default=true
            shift
            ;;
        --log_config)
            log_config_set=true
            custom_log_config="$2"
            remaining_args+=("--log_config" "$2")
            shift 2
            ;;
        --log_config=*)
            log_config_set=true
            custom_log_config="${1#*=}"
            remaining_args+=("$1")
            shift
            ;;
        *)
            remaining_args+=("$1")
            shift
            ;;
    esac
done

# Add async logging if flag is set AND no custom log config is provided
if [ "$async_logging_default" = true ] && [ "$log_config_set" = false ]; then
    echo "Async logging enabled (using default async configuration)"
    remaining_args+=("--log_config" "./target/classes/littleproxy_async_log4j2.xml")
elif [ "$async_logging_default" = true ] && [ "$log_config_set" = true ]; then
    echo "Warning: --async_logging_default flag ignored because custom --log_config is specified: $custom_log_config"
fi

javaArgs+=(-jar "$cp")
javaArgs+=("${remaining_args[@]}")

echo "Running using Java on path at `which java` with args ${javaArgs[*]}"
java "${javaArgs[@]}" || die "Java process exited abnormally"
