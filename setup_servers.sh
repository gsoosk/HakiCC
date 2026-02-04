#!/bin/bash

# =============================================================================
# Server Setup Script
# This script connects to remote servers and sets up Java, GitHub SSH keys,
# clones repositories, and creates Python environments
# =============================================================================

# Configuration: Add your server names/IPs here
SERVERS=(
    "server"
    "server1"
    "server2"
    "server3"
    "server4"
    # Add more servers as needed
)

# Repository URLs
REPO_OPENEVOLVE="git@github.com:gsoosk/openevolve_HakiCC.git"
REPO_HAKICC="git@github.com:gsoosk/HakiCC.git"

# Base directory for repositories on remote servers
REMOTE_BASE_DIR="~/projects"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# -----------------------------------------------------------------------------
# Helper functions
# -----------------------------------------------------------------------------

log_info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

log_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

log_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# -----------------------------------------------------------------------------
# Remote setup function - runs on each server
# -----------------------------------------------------------------------------

setup_server() {
    local server=$1
    
    echo ""
    echo "============================================================================="
    echo "Setting up server: $server"
    echo "============================================================================="
    
    ssh -o ConnectTimeout=10 -o StrictHostKeyChecking=accept-new "$server" bash -s << 'REMOTE_SCRIPT'
        set -e
        
        # Colors for output
        RED='\033[0;31m'
        GREEN='\033[0;32m'
        YELLOW='\033[1;33m'
        BLUE='\033[0;34m'
        NC='\033[0m'
        
        log_info() { echo -e "${BLUE}[INFO]${NC} $1"; }
        log_success() { echo -e "${GREEN}[SUCCESS]${NC} $1"; }
        log_warning() { echo -e "${YELLOW}[WARNING]${NC} $1"; }
        log_error() { echo -e "${RED}[ERROR]${NC} $1"; }
        
        # -----------------------------------------------------------------
        # 1. Install Java
        # -----------------------------------------------------------------
        log_info "Checking/Installing Java..."
        
        if command -v java &> /dev/null; then
            JAVA_VERSION=$(java -version 2>&1 | head -n 1)
            log_success "Java is already installed: $JAVA_VERSION"
        else
            log_info "Installing Java..."
            
            # Detect package manager and install Java
            if command -v apt-get &> /dev/null; then
                sudo apt-get update -qq
                sudo apt-get install -y default-jdk
            elif command -v yum &> /dev/null; then
                sudo yum install -y java-11-openjdk java-11-openjdk-devel
            elif command -v dnf &> /dev/null; then
                sudo dnf install -y java-11-openjdk java-11-openjdk-devel
            elif command -v pacman &> /dev/null; then
                sudo pacman -S --noconfirm jdk-openjdk
            elif command -v brew &> /dev/null; then
                brew install openjdk
            else
                log_error "Could not detect package manager. Please install Java manually."
                exit 1
            fi
            
            if command -v java &> /dev/null; then
                log_success "Java installed successfully"
            else
                log_error "Java installation failed"
                exit 1
            fi
        fi
        
        # -----------------------------------------------------------------
        # 2. Create GitHub SSH key if it doesn't exist
        # -----------------------------------------------------------------
        log_info "Checking/Creating GitHub SSH key..."
        
        SSH_KEY_PATH="$HOME/.ssh/id_ed25519"
        
        if [ -f "$SSH_KEY_PATH" ]; then
            log_success "SSH key already exists at $SSH_KEY_PATH"
        else
            log_info "Creating new SSH key..."
            mkdir -p "$HOME/.ssh"
            chmod 700 "$HOME/.ssh"
            
            # Generate SSH key without passphrase
            ssh-keygen -t ed25519 -C "$(whoami)@$(hostname)" -f "$SSH_KEY_PATH" -N ""
            
            # Start ssh-agent and add key
            eval "$(ssh-agent -s)" > /dev/null
            ssh-add "$SSH_KEY_PATH" 2>/dev/null || true
            
            log_success "SSH key created successfully"
        fi
        
        # Print the public key
        echo ""
        echo "============================================"
        echo "SSH PUBLIC KEY (add to GitHub):"
        echo "============================================"
        cat "${SSH_KEY_PATH}.pub"
        echo "============================================"
        echo ""
        
        # Configure SSH to use the key for GitHub
        if ! grep -q "github.com" "$HOME/.ssh/config" 2>/dev/null; then
            cat >> "$HOME/.ssh/config" << 'EOF'

Host github.com
    HostName github.com
    User git
    IdentityFile ~/.ssh/id_ed25519
    StrictHostKeyChecking accept-new
EOF
            chmod 600 "$HOME/.ssh/config"
            log_info "Added GitHub SSH config"
        fi
        
        # -----------------------------------------------------------------
        # 3. Install Python and pip if needed
        # -----------------------------------------------------------------
        log_info "Checking Python installation..."
        
        if ! command -v python3 &> /dev/null; then
            log_info "Installing Python3..."
            if command -v apt-get &> /dev/null; then
                sudo apt-get install -y python3 python3-pip python3-venv
            elif command -v yum &> /dev/null; then
                sudo yum install -y python3 python3-pip
            elif command -v dnf &> /dev/null; then
                sudo dnf install -y python3 python3-pip
            fi
        fi
        
        log_success "Python3 is available: $(python3 --version)"
        
        # -----------------------------------------------------------------
        # 4. Create base directory and clone/pull repositories
        # -----------------------------------------------------------------
        BASE_DIR="$HOME/projects"
        mkdir -p "$BASE_DIR"
        cd "$BASE_DIR"
        
        log_info "Working directory: $BASE_DIR"
        
        # Function to clone or pull a repository
        clone_or_pull() {
            local repo_url=$1
            local repo_name=$2
            
            if [ -d "$repo_name" ]; then
                log_info "Repository $repo_name exists, pulling latest changes..."
                cd "$repo_name"
                git fetch --all
                git pull --rebase || git pull
                cd ..
                log_success "Updated $repo_name"
            else
                log_info "Cloning $repo_name..."
                if git clone "$repo_url"; then
                    log_success "Cloned $repo_name"
                else
                    log_error "Failed to clone $repo_name - Make sure SSH key is added to GitHub!"
                    return 1
                fi
            fi
        }
        
        # Clone/pull openevolve_HakiCC
        clone_or_pull "git@github.com:gsoosk/openevolve_HakiCC.git" "openevolve_HakiCC"
        
        # Clone/pull HakiCC
        clone_or_pull "git@github.com:gsoosk/HakiCC.git" "HakiCC"
        
        # -----------------------------------------------------------------
        # 5. Create virtual environments
        # -----------------------------------------------------------------
        log_info "Setting up Python virtual environments..."
        
        # Setup openevolve_HakiCC environment
        if [ -d "openevolve_HakiCC" ]; then
            log_info "Setting up environment for openevolve_HakiCC..."
            cd openevolve_HakiCC
            
            if [ ! -d "venv" ]; then
                python3 -m venv venv
                log_success "Created virtual environment for openevolve_HakiCC"
            else
                log_info "Virtual environment already exists for openevolve_HakiCC"
            fi
            
            # Activate and install requirements
            source venv/bin/activate
            pip install --upgrade pip
            
            # Install the package using pip install .
            if [ -f "setup.py" ] || [ -f "pyproject.toml" ]; then
                log_info "Installing openevolve_HakiCC package..."
                pip install .
                log_success "Installed openevolve_HakiCC package"
            elif [ -f "requirements.txt" ]; then
                log_info "Installing from requirements.txt..."
                pip install -r requirements.txt
                log_success "Installed requirements for openevolve_HakiCC"
            else
                log_warning "No setup.py, pyproject.toml, or requirements.txt found"
            fi
            
            deactivate
            cd "$BASE_DIR"
        fi
        
        # Setup HakiCC environment
        if [ -d "HakiCC" ]; then
            log_info "Setting up environment for HakiCC..."
            cd HakiCC
            
            if [ ! -d "venv" ]; then
                python3 -m venv venv
                log_success "Created virtual environment for HakiCC"
            else
                log_info "Virtual environment already exists for HakiCC"
            fi
            
            # Activate and install requirements if they exist
            source venv/bin/activate
            pip install --upgrade pip
            
            if [ -f "requirements.txt" ]; then
                pip install -r requirements.txt
                log_success "Installed requirements for HakiCC"
            elif [ -f "setup.py" ] || [ -f "pyproject.toml" ]; then
                pip install .
                log_success "Installed HakiCC package"
            fi
            
            deactivate
            cd "$BASE_DIR"
        fi
        
        # -----------------------------------------------------------------
        # Done!
        # -----------------------------------------------------------------
        echo ""
        log_success "Server setup complete!"
        echo ""
        echo "Projects location: $BASE_DIR"
        echo "  - openevolve_HakiCC: $BASE_DIR/openevolve_HakiCC (venv: venv)"
        echo "  - HakiCC: $BASE_DIR/HakiCC (venv: venv)"
        echo ""
        echo "To activate environments:"
        echo "  cd $BASE_DIR/openevolve_HakiCC && source venv/bin/activate"
        echo "  cd $BASE_DIR/HakiCC && source venv/bin/activate"
        echo ""
REMOTE_SCRIPT
    
    local status=$?
    if [ $status -eq 0 ]; then
        log_success "Server $server setup completed successfully"
    else
        log_error "Server $server setup failed with exit code $status"
    fi
    
    return $status
}

# -----------------------------------------------------------------------------
# Main execution
# -----------------------------------------------------------------------------

main() {
    echo "============================================================================="
    echo "Server Setup Script"
    echo "============================================================================="
    echo ""
    
    if [ ${#SERVERS[@]} -eq 0 ]; then
        log_error "No servers configured. Please edit the SERVERS array in this script."
        exit 1
    fi
    
    log_info "Servers to setup: ${SERVERS[*]}"
    echo ""
    
    local failed_servers=()
    local success_count=0
    
    for server in "${SERVERS[@]}"; do
        if setup_server "$server"; then
            ((success_count++))
        else
            failed_servers+=("$server")
        fi
    done
    
    # Summary
    echo ""
    echo "============================================================================="
    echo "SUMMARY"
    echo "============================================================================="
    log_info "Total servers: ${#SERVERS[@]}"
    log_success "Successful: $success_count"
    
    if [ ${#failed_servers[@]} -gt 0 ]; then
        log_error "Failed: ${#failed_servers[@]}"
        log_error "Failed servers: ${failed_servers[*]}"
        exit 1
    fi
    
    log_success "All servers setup completed successfully!"
}

# Run main function
main "$@"
