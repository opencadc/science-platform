#!/bin/sh

# Two variables are expected:
# REGISTRY_URL
#   The URL to the registry to use.
#   Example REGISTRY_URL=https://example.org/reg
#
# POSIX_MAPPER_URI
#   The IVO URI of the POSIX Mapper.  No absolute URLs.
#   Example POSIX_MAPPER_URI=ivo://example.org/posix-mapper
#

# It is expected that a process will have backed up the original /etc/passwd and /etc/groups files
# to the specified ORIGINAL_ files.

ORIGINAL_PASSWD_FILE="/etc-passwd/passwd-orig"
ORIGINAL_GROUP_FILE="/etc-group/group-orig"

PASSWD_FILE="/etc-passwd/passwd"
GROUP_FILE="/etc-group/group"

TOKEN_AUTHORIZATION_HEADER="authorization: api-key"

CADC_PROXY_CERT_FILE="/root/.ssl/cadcproxy.pem"
TOKEN_FILE="/config/keys/sshd-api-key"

TMP_FILE_NAME=`hexdump -n 8 -v -e '/1 "%02X"' -e '/8 "\n"' /dev/urandom`
LOCAL_CAPABILITIES_FILE="/tmp/${TMP_FILE_NAME}-capabilities.xml"

if [[ ! -f "${TOKEN_FILE}" && ! -f "${CADC_PROXY_CERT_FILE}" ]]; then
    echo "One of the required files (${TOKEN_FILE}, ${CADC_PROXY_CERT_FILE}) containing the POSIX Mapper API Key or client certificates is missing."
    exit 1
elif [[ -z "${POSIX_MAPPER_URI}" ]] ; then
    echo "Required variable POSIX_MAPPER_URI is not set."
    exit 2
elif [[ -z "${REGISTRY_URL}" && "${POSIX_MAPPER_URI}" != http* ]] ; then
    echo "Required variable REGISTRY_URL is not set if POSIX_MAPPER_URI is not an absolute URL."
    exit 3
else
    echo "Using Registry at ${REGISTRY_URL}"
fi

UID_STANDARD_URI="http://www.opencadc.org/std/posix#user-mapping-0.1"
GID_STANDARD_URI="http://www.opencadc.org/std/posix#group-mapping-0.1"

if [[ "${POSIX_MAPPER_URI}" == http* ]] ; then
    # Remove trailing slashes
    TRIMMED_POSIX_MAPPER_URL=`echo "${POSIX_MAPPER_URI}" | tr -s \/ | sed 's/\(.*\)\/$/\1/'`
    POSIX_MAPPER_CAPABILITIES_URL="${TRIMMED_POSIX_MAPPER_URL}/capabilities"
else
    POSIX_MAPPER_CAPABILITIES_URL=`curl -SsL ${REGISTRY_URL}/resource-caps | grep ${POSIX_MAPPER_URI} | awk -F = '{print $2}' | xargs`
fi

echo "Using POSIX Mapper service at ${POSIX_MAPPER_CAPABILITIES_URL}"

# Dump the contents locally.
curl -SsL ${POSIX_MAPPER_CAPABILITIES_URL} > ${LOCAL_CAPABILITIES_FILE}
echo "Got the latest capabilities at ${LOCAL_CAPABILITIES_FILE}."

UID_STANDARD_LINE_NUMBER=`grep -n "${UID_STANDARD_URI}" ${LOCAL_CAPABILITIES_FILE} | cut -f1 -d:`
GID_STANDARD_LINE_NUMBER=`grep -n "${GID_STANDARD_URI}" ${LOCAL_CAPABILITIES_FILE} | cut -f1 -d:`

echo "Found ${UID_STANDARD_URI} at line number ${UID_STANDARD_LINE_NUMBER}"
echo "Found ${GID_STANDARD_URI} at line number ${GID_STANDARD_LINE_NUMBER}"

UID_URL=`tail -n +${UID_STANDARD_LINE_NUMBER} ${LOCAL_CAPABILITIES_FILE} | grep accessURL -m 1 | awk -F \> '{print $2}' | awk -F \< '{print $1}'`
echo "Will obtain UID information from ${UID_URL}"

GID_URL=`tail -n +${GID_STANDARD_LINE_NUMBER} ${LOCAL_CAPABILITIES_FILE} | grep accessURL -m 1 | awk -F \> '{print $2}' | awk -F \< '{print $1}'`
echo "Will obtain GID information from ${GID_URL}"

if [[ -z "${UID_URL}" || "X${UID_URL}" = "X" ]] ; then
    echo "No URL found for POSIX Mapper UID."
    exit 4
elif [[ -z "${GID_URL}" || "X${GID_URL}" = "X" ]] ; then
    echo "No URL found for POSIX Mapper GID."
    exit 5
else
    cp "${ORIGINAL_PASSWD_FILE}" "${PASSWD_FILE}"
    cp "${ORIGINAL_GROUP_FILE}" "${GROUP_FILE}"

    # For the case of using the CADC client certificate.
    if [[ -f "${CADC_PROXY_CERT_FILE}" ]] ; then
        curl -SsL -E ${CADC_PROXY_CERT_FILE} "${UID_URL}" | sed 's/^\([a-z]*\):\(.*\):::$/\1:\2::\/home\/\1:/' >> "${PASSWD_FILE}"
        curl -SsL -E ${CADC_PROXY_CERT_FILE} "${GID_URL}" >> "${GROUP_FILE}"
    else
        TOKEN=`cat ${TOKEN_FILE}`
        curl -SsL --header "${TOKEN_AUTHORIZATION_HEADER} ${TOKEN}" "${UID_URL}" | sed 's/^\([a-z]*\):\(.*\):::$/\1:\2::\/home\/\1:/' >> "${PASSWD_FILE}"
        curl -SsL --header "${TOKEN_AUTHORIZATION_HEADER} ${TOKEN}" "${GID_URL}" >> "${GROUP_FILE}"
    fi
fi
