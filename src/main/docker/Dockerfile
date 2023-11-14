FROM amazoncorretto:17

ENV ARTIFACT sql-explorer.jar
ENV APP_PROP_CONFIG_DIR /etc/sql-explorer/config/,optional:/etc/sql-explorer/secrets/

WORKDIR /app
#Copy artifact
COPY libs/$ARTIFACT $ARTIFACT

# todo
#RUN chown $APP_USER:$APP_USER $HOME/$ARTIFACT \
#    && chmod o-rwx $HOME/$ARTIFACT

# Switch to application user
# USER $APP_USER

# volume mount
VOLUME /etc/sql-explorer/config

ENTRYPOINT exec java $JAVA_OPTS \
    -Dspring.config.additional-location=$APP_PROP_CONFIG_DIR \
    -Dspring.profiles.include=$ENVIRONMENT_PROFILE \
    -jar $ARTIFACT
