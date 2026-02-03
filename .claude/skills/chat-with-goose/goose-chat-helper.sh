#!/bin/bash

# Goose Agent Chat Helper Script
# Handles authentication, session management, and message sending

set -e

DEFAULT_APP_URL="https://goose-agent-chat.apps.tas-ndc.kuhn-labs.com"
APP_URL=""  # Will be set from argument, cache, or default
USERNAME="user"
PASSWORD=""  # Will be provided as argument or prompted
COOKIE_FILE="/tmp/goose-chat-cookies.txt"
SESSION_FILE="/tmp/goose-chat-session.txt"
PASSWORD_FILE="/tmp/goose-chat-password.txt"
URL_FILE="/tmp/goose-chat-url.txt"

# Colors for output
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m' # No Color

# Function to get URL from argument, cache, or default
get_url() {
    local url_to_use=""

    # If URL provided as argument, use it
    if [ -n "$APP_URL" ]; then
        url_to_use="$APP_URL"
    # Check for cached URL
    elif [ -f "$URL_FILE" ]; then
        local cached_url=$(cat "$URL_FILE")
        if [ -n "$cached_url" ]; then
            url_to_use="$cached_url"
        fi
    fi

    # If we still don't have a URL, use the default
    if [ -z "$url_to_use" ]; then
        url_to_use="$DEFAULT_APP_URL"
    fi

    # Cache the URL for future use (if not default and not already cached)
    if [ "$url_to_use" != "$DEFAULT_APP_URL" ]; then
        if [ ! -f "$URL_FILE" ] || [ "$(cat "$URL_FILE" 2>/dev/null)" != "$url_to_use" ]; then
            echo "$url_to_use" > "$URL_FILE"
            chmod 600 "$URL_FILE"
        fi
    fi

    echo "$url_to_use"
}

# Function to get password from cache or prompt
get_password() {
    local password_to_use=""

    # If password provided as argument, use it
    if [ -n "$PASSWORD" ]; then
        password_to_use="$PASSWORD"
    # Check for cached password
    elif [ -f "$PASSWORD_FILE" ]; then
        local cached_password=$(cat "$PASSWORD_FILE")
        if [ -n "$cached_password" ]; then
            password_to_use="$cached_password"
        fi
    fi

    # If we still don't have a password, prompt for it
    if [ -z "$password_to_use" ]; then
        echo -e "${YELLOW}Password required for authentication${NC}" >&2
        read -s -p "Enter password: " input_password
        echo "" >&2

        if [ -z "$input_password" ]; then
            echo -e "${RED}✗ Password cannot be empty${NC}" >&2
            return 1
        fi
        password_to_use="$input_password"
    fi

    # Cache the password for future use (if not already cached)
    if [ ! -f "$PASSWORD_FILE" ] || [ "$(cat "$PASSWORD_FILE" 2>/dev/null)" != "$password_to_use" ]; then
        echo "$password_to_use" > "$PASSWORD_FILE"
        chmod 600 "$PASSWORD_FILE"
    fi

    echo "$password_to_use"
}

# Function to check authentication
check_auth() {
    local auth_status=$(curl -s -b "$COOKIE_FILE" "$APP_URL/auth/status" 2>/dev/null || echo "{}")
    echo "$auth_status" | grep -q '"authenticated":true'
}

# Function to authenticate
authenticate() {
    local password=$(get_password)
    if [ $? -ne 0 ]; then
        return 1
    fi

    echo -e "${BLUE}Authenticating...${NC}" >&2
    curl -s -c "$COOKIE_FILE" -b "$COOKIE_FILE" -L \
        -X POST "$APP_URL/auth/login" \
        -d "username=$USERNAME&password=$password" > /dev/null

    if check_auth; then
        echo -e "${GREEN}✓ Authenticated successfully${NC}" >&2
        return 0
    else
        echo -e "${RED}✗ Authentication failed - invalid password${NC}" >&2
        # Clear cached password if authentication failed
        rm -f "$PASSWORD_FILE"
        return 1
    fi
}

# Function to get or create session
get_session() {
    local session_id=""

    # Check for existing session
    if [ -f "$SESSION_FILE" ]; then
        session_id=$(cat "$SESSION_FILE")
        local status=$(curl -s -b "$COOKIE_FILE" "$APP_URL/api/chat/sessions/${session_id}/status" 2>/dev/null || echo "{}")

        if echo "$status" | grep -q '"active":true'; then
            echo -e "${GREEN}✓ Using existing session: $session_id${NC}" >&2
            echo "$session_id"
            return 0
        else
            echo -e "${YELLOW}Session expired, creating new session...${NC}" >&2
            rm -f "$SESSION_FILE"
        fi
    fi

    # Create new session
    local response=$(curl -s -b "$COOKIE_FILE" \
        -X POST "$APP_URL/api/chat/sessions" \
        -H "Content-Type: application/json" \
        -d '{}')

    session_id=$(echo "$response" | grep -o '"sessionId":"[^"]*"' | cut -d'"' -f4)

    if [ -n "$session_id" ]; then
        echo "$session_id" > "$SESSION_FILE"
        echo -e "${GREEN}✓ Created new session: $session_id${NC}" >&2
        echo "$session_id"
        return 0
    else
        echo -e "${RED}✗ Failed to create session${NC}" >&2
        return 1
    fi
}

# Function to send message
send_message() {
    local session_id="$1"
    local message="$2"

    # URL-encode the message
    local message_encoded=$(printf %s "$message" | jq -sRr @uri)

    echo -e "\n${BLUE}Goose Response:${NC}" >&2
    echo -e "${BLUE}─────────────────────────────────────────────────────${NC}" >&2

    # Stream response and parse SSE events
    curl -s -b "$COOKIE_FILE" -N \
        "$APP_URL/api/chat/sessions/${session_id}/stream?message=${message_encoded}" \
        --max-time 120 | while IFS= read -r line; do

        if [[ "$line" =~ ^event:(.+)$ ]]; then
            event_type="${BASH_REMATCH[1]}"
        elif [[ "$line" =~ ^data:(.+)$ ]]; then
            data="${BASH_REMATCH[1]}"

            case "$event_type" in
                token)
                    # Token data is JSON-encoded, decode it
                    decoded=$(echo "$data" | jq -r '.' 2>/dev/null || echo "$data")
                    printf "%s" "$decoded"
                    ;;
                complete)
                    echo -e "\n${BLUE}─────────────────────────────────────────────────────${NC}" >&2
                    echo -e "${GREEN}✓ Complete (${data} tokens)${NC}" >&2
                    ;;
                error)
                    echo -e "\n${RED}✗ Error: $data${NC}" >&2
                    ;;
                activity)
                    # Optionally show activity (tool calls) - currently suppressed
                    # echo -e "\n${YELLOW}[Activity: $data]${NC}" >&2
                    ;;
            esac
        fi
    done

    echo "" >&2
}

# Main execution
main() {
    local message=""

    # Parse arguments
    while [[ $# -gt 0 ]]; do
        case $1 in
            --url=*)
                APP_URL="${1#*=}"
                shift
                ;;
            --url)
                APP_URL="$2"
                shift 2
                ;;
            --password=*)
                PASSWORD="${1#*=}"
                shift
                ;;
            --password)
                PASSWORD="$2"
                shift 2
                ;;
            *)
                # Everything else is part of the message
                if [ -z "$message" ]; then
                    message="$1"
                else
                    message="$message $1"
                fi
                shift
                ;;
        esac
    done

    # Check if message is provided
    if [ -z "$message" ]; then
        echo "Usage: $0 [--url URL] [--password PASSWORD] <message>"
        echo ""
        echo "Examples:"
        echo "  $0 \"What are Spring Boot best practices?\""
        echo "  $0 --password=tanzu \"How do I optimize queries?\""
        echo "  $0 --url https://my-app.example.com --password tanzu \"Hello\""
        echo ""
        echo "Options:"
        echo "  --url URL        Application URL (default: $DEFAULT_APP_URL)"
        echo "  --password PWD   Password for authentication (cached after first use)"
        echo ""
        echo "Cached files:"
        echo "  URL:      $URL_FILE"
        echo "  Password: $PASSWORD_FILE"
        echo "  Session:  $SESSION_FILE"
        echo "  Cookies:  $COOKIE_FILE"
        exit 1
    fi

    # Get the URL to use (from argument, cache, or default)
    APP_URL=$(get_url)

    # Display which URL is being used (if not default)
    if [ "$APP_URL" != "$DEFAULT_APP_URL" ]; then
        echo -e "${BLUE}Using custom URL: $APP_URL${NC}" >&2
    fi

    # Ensure we're authenticated
    if ! check_auth; then
        authenticate || exit 1
    fi

    # Get or create session
    local session_id=$(get_session) || exit 1

    # Send message
    send_message "$session_id" "$message"
}

main "$@"
