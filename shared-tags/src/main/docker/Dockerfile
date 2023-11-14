
FROM liquibase/liquibase:4.24

RUN lpm add mysql --global

ADD liquibase/changelog /changelog
RUN chown -R $APP_USER:$APP_USER /changelog
