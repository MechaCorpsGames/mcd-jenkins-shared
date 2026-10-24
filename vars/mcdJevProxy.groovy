import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException

// Only fixed tokens leave the shell. A host-file secret is never a Groovy value.
String keyReader() {
    return '''
read_jev_key() {
    LC_ALL=C
    export LC_ALL
    jev_key=''
    jev_reason=invalid
    case "$MCD_JEV_KEY_FILE" in
        ''|/*|*[!A-Za-z0-9._/-]*|..|../*|*/../*|*/..) return 1 ;;
    esac
    jev_file="/var/opt/mechacorpsgames/Src/$MCD_JEV_KEY_FILE"
    jev_reason=missing
    [ -f "$jev_file" ] || return 1
    jev_reason=unreadable
    [ -r "$jev_file" ] || return 1
    if jev_count=$(grep -c '^[[:space:]]*TYPESAFE_API_KEY[[:space:]]*=' "$jev_file" 2>/dev/null); then
        :
    else
        [ "$?" -eq 1 ] || return 1
    fi
    case "$jev_count" in
        0) jev_reason=absent; return 1 ;;
        1) ;;
        *) jev_reason=duplicate; return 1 ;;
    esac
    if jev_key=$(sed -n '/^[[:space:]]*TYPESAFE_API_KEY[[:space:]]*=/ {
        s/^[^=]*=//
        s/\\r//g
        s/^[[:space:]]*//
        s/[[:space:]]*$//
        p
    }' "$jev_file" 2>/dev/null); then
        :
    else
        return 1
    fi
    case "$jev_key" in
        \\"*\\") jev_key=${jev_key#\\"}; jev_key=${jev_key%\\"} ;;
    esac
    jev_reason=rejected
    case "$jev_key" in
        ''|*[!A-Za-z0-9._~+/=:@-]*) return 1 ;;
    esac
}
'''
}

boolean appendKey(String keyFile, String envFile) {
    String outcome
    withEnv(["MCD_JEV_KEY_FILE=${keyFile}", "MCD_JEV_ENV_FILE=${envFile}"]) {
        outcome = sh(script: '#!/bin/sh -e\nset +x\n' + keyReader() + '''
if read_jev_key; then
    jev_env="/var/opt/mechacorpsgames/Src/$MCD_JEV_ENV_FILE"
    printf 'TYPESAFE_API_KEY=%s\\n' "$jev_key" >> "$jev_env"
    if [ "$(stat -c %u "$jev_env")" = "$(id -u)" ]; then
        chmod 600 "$jev_env"
    fi
    printf '%s' ready
else
    printf '%s' "$jev_reason"
fi
''', returnStdout: true).trim()
    }
    if (outcome == 'ready') {
        return true
    }
    def reasons = [
        invalid: 'typesafeKeyFile invalid',
        missing: "key file ${keyFile} not found",
        unreadable: "key file ${keyFile} not readable by the build account",
        absent: "no TYPESAFE_API_KEY line in ${keyFile}",
        duplicate: "more than one TYPESAFE_API_KEY line in ${keyFile}",
        rejected: 'key value rejected (empty or unexpected characters)'
    ]
    // An unexpected shell failure remains a failure, like env-file generation.
    if (!reasons.containsKey(outcome)) {
        error('Jev key-file read returned an unknown result')
    }
    mcdUnstableReason("Jev not offered: ${reasons[outcome]}")
    currentBuild.result = 'UNSTABLE'
    return false
}

void check(Map config, String containerName, String keyState) {
    try {
        String verdict
        withEnv(["MCD_JEV_KEY_FILE=${config.typesafeKeyFile ?: ''}",
                 "MCD_JEV_CONTAINER=${containerName}",
                 "MCD_JEV_PORT=${config.wsPort ?: config.tcpPort + 1}",
                 "MCD_JEV_KEY_STATE=${keyState}"]) {
            // The shell timeout bounds Docker as well as the HTTP poll. A Jenkins
            // interruption is never mistaken for this probe's ordinary timeout.
            verdict = sh(script: '#!/bin/sh -e\nset +x\ntimeout 25s /bin/sh <<\'JEV_CHECK\'\nset +x\n' + keyReader() + '''
deadline=$(( $(date +%s) + 20 ))
jev_listing=''
while [ "$(date +%s)" -lt "$deadline" ]; do
    if jev_response=$(curl --silent --fail --connect-timeout 1 --max-time 2 "http://127.0.0.1:$MCD_JEV_PORT/api/bots" 2>/dev/null); then
        if jev_listing=$(printf '%s' "$jev_response" | python3 -c '
import json, sys
data = json.load(sys.stdin)
bots = data["bots"]
if not isinstance(bots, list) or not all(isinstance(bot, dict) and isinstance(bot.get("id"), str) for bot in bots):
    raise ValueError("invalid bot list")
print("listed" if any(bot["id"] == "jev" for bot in bots) else "absent")
' 2>/dev/null); then
            break
        fi
    fi
    sleep 1
done
if [ -z "$jev_listing" ]; then
    printf '%s' warn-query
    exit 0
fi
if [ "$MCD_JEV_KEY_STATE" = ready ] && ! read_jev_key; then
    printf '%s' warn-reread
    exit 0
fi
if jev_container_key=$(docker exec "$MCD_JEV_CONTAINER" sh -c '
    if [ -n "${TYPESAFE_API_KEY:-}" ]; then
        printf "present:"
        printf "%s" "$TYPESAFE_API_KEY" | sha256sum | cut -d" " -f1
    else
        printf "absent"
    fi
' 2>/dev/null); then
    case "$jev_container_key" in
        absent) ;;
        *) if ! printf '%s' "$jev_container_key" | grep -Eq '^present:[a-f0-9]{64}$'; then
               printf '%s' warn-container; exit 0
           fi ;;
    esac
else
    printf '%s' warn-container
    exit 0
fi
if [ "$MCD_JEV_KEY_STATE" = ready ]; then
    jev_expected="present:$(printf '%s' "$jev_key" | sha256sum | cut -d' ' -f1)"
    if [ "$jev_expected" != "$jev_container_key" ]; then
        printf '%s' key-not-current
    elif [ "$jev_listing" != listed ]; then
        printf '%s' not-listed
    else
        if jev_libraries=$(docker exec "$MCD_JEV_CONTAINER" sh -c '
            relative=$(cat /app/testclient-versions/latest.txt) || exit 2
            binary="/app/testclient-versions/$relative"
            [ -f "$binary" ] || exit 2
            deps=$(ldd "$binary" 2>/dev/null) || exit 2
            case "$deps" in
                *"not found"*) printf missing ;;
                *) printf resolved ;;
            esac
        ' 2>/dev/null); then
            case "$jev_libraries" in
                resolved) printf '%s' offered ;;
                missing) printf '%s' missing-libraries ;;
                *) printf '%s' warn-libraries ;;
            esac
        else
            printf '%s' warn-libraries
        fi
    fi
elif [ "$jev_container_key" != absent ] || [ "$jev_listing" = listed ]; then
    printf '%s' unexpected
else
    printf '%s' not-offered
fi
JEV_CHECK
''', returnStdout: true).trim()
        }
        def reasons = [
            'key-not-current': 'key not current',
            'not-listed': 'Jev expected but not listed',
            'missing-libraries': 'Jev bot has unresolved shared libraries',
            unexpected: keyState == 'unusable' ? 'Jev key or listing still present although the key file is unusable' :
                'Jev key or listing present on an environment that did not opt in'
        ]
        if (reasons.containsKey(verdict)) {
            mcdUnstableReason(reasons[verdict])
            currentBuild.result = 'UNSTABLE'
            echo "Jev: deployment check failed (${reasons[verdict]})"
        } else if (verdict == 'offered') {
            echo 'Jev: offered in /api/bots'
        } else if (verdict == 'not-offered') {
            echo "Jev: not offered on this environment (${keyState == 'unusable' ? 'key file unusable' : 'not configured'})"
        } else {
            def warnings = [
                'warn-query': 'proxy did not return a valid bot list',
                'warn-reread': 'key file changed or became unreadable',
                'warn-container': 'container environment could not be checked',
                'warn-libraries': 'bot libraries could not be checked'
            ]
            echo "WARN Jev check skipped: ${warnings[verdict] ?: 'probe returned an unknown result'}"
        }
    } catch (FlowInterruptedException interrupted) {
        throw interrupted
    } catch (InterruptedException interrupted) {
        throw interrupted
    } catch (Exception ignored) {
        echo 'WARN Jev check skipped: probe failed or timed out'
    }
}
