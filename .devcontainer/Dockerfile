FROM mcr.microsoft.com/devcontainers/python:3.11

# --- tshark (Wireshark CLI) -------------------------------------------------
# We only ever read already-captured .pcapng files in this container (the
# actual USB capture happens on a Windows PC with USBPcap — a cloud
# Codespace has no access to your physical USB hardware). Because of that,
# we explicitly decline the "allow non-superusers to capture" debconf prompt
# so the install runs non-interactively and doesn't hang the build.
RUN echo "wireshark-common wireshark-common/install-setuid boolean false" | debconf-set-selections \
    && apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
        tshark \
        xxd \
    && apt-get clean \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /workspaces

COPY requirements.txt /tmp/requirements.txt
RUN pip install --no-cache-dir -r /tmp/requirements.txt
