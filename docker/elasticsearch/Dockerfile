FROM elasticsearch:8.15.3

USER root
RUN bin/elasticsearch-plugin install --batch \
    https://release.infinilabs.com/analysis-ik/stable/elasticsearch-analysis-ik-8.15.3.zip
USER elasticsearch
