# Copyright (c) 2020-2021, Jan Cajthaml <jan.cajthaml@gmail.com>
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

FROM --platform=linux/amd64 amd64/debian:sid-slim

COPY packaging/bin/* /opt/artifacts/

USER root

RUN apt-get update

RUN find /opt/artifacts -name data-warehouse_*_amd64.deb | xargs -I{} -tx apt-get install -y -f {}

RUN apt-get install -y openjdk-17-jre

ENV PROG_HOME=/usr/share/openbank/data-warehouse

ENTRYPOINT [ "data-warehouse" ]

