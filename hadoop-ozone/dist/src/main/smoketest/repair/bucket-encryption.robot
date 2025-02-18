# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

*** Settings ***
Resource    ../lib/os.robot
Resource    ../ozone-lib/shell.robot

*** Variables ***
${ENCRYPTION_KEY}        key1


*** Keywords ***
Verify Bucket Encryption Key
    [arguments]    ${bucket}    ${expected}
    ${output} =    Execute    ozone sh bucket info ${bucket}
    ${actual} =    Execute    echo '${output}' | jq -r '.encryptionKeyName'
    Should Be Equal     ${expected}    ${actual}


*** Test Cases ***
Set Bucket Encryption Key
    ${random} =    Generate Random String  10  [NUMBERS]
    ${bucket} =    Set Variable    /vol${random}/encrypted

    Ozone Shell Batch   volume create /vol${random}
    ...                 bucket create ${bucket}
    Verify Bucket Encryption Key    ${bucket}    null

    Execute             ozone sh bucket set-encryption-key -k ${ENCRYPTION_KEY} ${bucket}
    Verify Bucket Encryption Key    ${bucket}    ${ENCRYPTION_KEY}
