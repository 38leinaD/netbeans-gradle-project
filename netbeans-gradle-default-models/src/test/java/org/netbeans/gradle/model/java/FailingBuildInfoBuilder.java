package org.netbeans.gradle.model.java;

import org.gradle.tooling.BuildController;
import org.netbeans.gradle.model.BuildInfoBuilder;
import org.netbeans.gradle.model.util.BuilderUtils;

public final class FailingBuildInfoBuilder implements BuildInfoBuilder<Void> {
    private static final long serialVersionUID = 1L;

    private final String exceptionMessage;

    public FailingBuildInfoBuilder(String exceptionMessage) {
        if (exceptionMessage == null) throw new NullPointerException("exceptionMessage");
        this.exceptionMessage = exceptionMessage;
    }

    public Void getInfo(BuildController controller) {
        throw new NotSerializableException(exceptionMessage);
    }

    public String getName() {
        return BuilderUtils.getNameForGenericBuilder(this, exceptionMessage);
    }

    @SuppressWarnings("serial")
    private static final class NotSerializableException extends RuntimeException {
        public final Object blockerOfSerialization;

        public NotSerializableException(String message) {
            super(message);
            blockerOfSerialization = new Object();
        }
    }
}