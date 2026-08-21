package db.mysqlmigration;

import org.flywaydb.core.api.FlywayException;

/** Converts inline PostgreSQL references into enforced MySQL foreign keys. */
final class MySqlV50RelationalNormalizer {

    private static final String TABLE_OPTIONS_MARKER = "\n) ENGINE=InnoDB";
    private static final String REQUIRED_INSTANCE_REFERENCE =
        "instance_id varchar(36) not null references ap_approval_instance(instance_id)";
    private static final String OPTIONAL_INSTANCE_REFERENCE =
        "instance_id varchar(36) references ap_approval_instance(instance_id)";
    private static final String OPTIONAL_TASK_REFERENCE =
        "task_id varchar(36) references ap_approval_task(task_id)";

    private MySqlV50RelationalNormalizer() {
    }

    static String normalize(String command) {
        if (MySqlV50Normalizer.createsTable(command, "ap_approval_task")) {
            String normalized = replace(
                command,
                REQUIRED_INSTANCE_REFERENCE,
                "instance_id varchar(36) not null",
                "approval task instance reference"
            );
            return append(normalized, """
                    constraint fk_approval_task_instance
                        foreign key (instance_id)
                        references ap_approval_instance (instance_id)
                """);
        }
        if (MySqlV50Normalizer.createsTable(command, "ap_approval_message")) {
            String normalized = replace(
                command,
                REQUIRED_INSTANCE_REFERENCE,
                "instance_id varchar(36) not null",
                "approval message instance reference"
            );
            normalized = replace(
                normalized,
                OPTIONAL_TASK_REFERENCE,
                "task_id varchar(36)",
                "approval message task reference"
            );
            return append(normalized, """
                    constraint fk_approval_message_instance
                        foreign key (instance_id)
                        references ap_approval_instance (instance_id),
                    constraint fk_approval_message_task
                        foreign key (task_id)
                        references ap_approval_task (task_id)
                """);
        }
        if (MySqlV50Normalizer.createsTable(command, "ap_approval_comment")) {
            String normalized = replace(
                command,
                REQUIRED_INSTANCE_REFERENCE,
                "instance_id varchar(36) not null",
                "approval comment instance reference"
            );
            return append(normalized, """
                    constraint fk_approval_comment_instance
                        foreign key (instance_id)
                        references ap_approval_instance (instance_id)
                """);
        }
        if (MySqlV50Normalizer.createsTable(command, "ap_approval_attachment")) {
            String normalized = replace(
                command,
                OPTIONAL_INSTANCE_REFERENCE,
                "instance_id varchar(36)",
                "approval attachment instance reference"
            );
            return append(normalized, """
                    constraint fk_approval_attachment_instance
                        foreign key (instance_id)
                        references ap_approval_instance (instance_id)
                """);
        }
        if (MySqlV50Normalizer.createsTable(command, "ap_form_submission")) {
            String normalized = replace(
                command,
                REQUIRED_INSTANCE_REFERENCE,
                "instance_id varchar(36) not null",
                "form submission instance reference"
            );
            return append(normalized, """
                    constraint fk_form_submission_instance
                        foreign key (instance_id)
                        references ap_approval_instance (instance_id)
                """);
        }
        if (MySqlV50Normalizer.createsTable(
            command,
            "ap_form_submission_revision"
        )) {
            String normalized = replace(
                command,
                REQUIRED_INSTANCE_REFERENCE,
                "instance_id varchar(36) not null",
                "form revision instance reference"
            );
            return append(normalized, """
                    constraint fk_form_submission_revision_instance
                        foreign key (instance_id)
                        references ap_approval_instance (instance_id)
                """);
        }
        return command;
    }

    private static String append(String command, String constraints) {
        int marker = command.lastIndexOf(TABLE_OPTIONS_MARKER);
        if (marker < 0) {
            throw new FlywayException(
                "MySQL baseline table options marker is missing for governed foreign key"
            );
        }
        return command.substring(0, marker)
            + ",\n"
            + constraints.stripTrailing()
            + command.substring(marker);
    }

    private static String replace(
        String command,
        String expected,
        String replacement,
        String boundary
    ) {
        return MySqlV50Normalizer.requireReplace(
            command,
            expected,
            replacement,
            boundary
        );
    }
}
