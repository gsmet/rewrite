/*
 * Copyright 2021 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * https://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.openrewrite.maven;

import java.util.Optional;

import org.openrewrite.ExecutionContext;
import org.openrewrite.Option;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.internal.lang.Nullable;
import org.openrewrite.xml.AddToTagVisitor;
import org.openrewrite.xml.ChangeTagValueVisitor;
import org.openrewrite.xml.tree.Xml;

import lombok.EqualsAndHashCode;
import lombok.Value;

@Value
@EqualsAndHashCode(callSuper = true)
public class ChangeDependencyGroupIdAndArtifactId extends Recipe {
    @Option(displayName = "Old groupId",
            description = "The old groupId to replace. The groupId is the first part of a dependency coordinate 'com.google.guava:guava:VERSION'.",
            example = "org.openrewrite.recipe")
    String oldGroupId;

    @Option(displayName = "Old artifactId",
            description = "The old artifactId to replace. The artifactId is the second part of a dependency coordinate 'com.google.guava:guava:VERSION'.",
            example = "rewrite-testing-frameworks")
    String oldArtifactId;

    @Option(displayName = "New groupId",
            description = "The new groupId to use.",
            example = "corp.internal.openrewrite.recipe")
    String newGroupId;

    @Option(displayName = "New artifactId",
            description = "The new artifactId to use.",
            example = "rewrite-testing-frameworks")
    String newArtifactId;

    @Option(displayName = "New version",
            description = "The new version to use.",
            example = "2.0.0",
            required = false)
    @Nullable
    String newVersion;

    @Option(displayName = "New classifier",
            description = "The new classifier to use.",
            example = "jakarta",
            required = false)
    @Nullable
    String newClassifier;

    @Override
    public String getDisplayName() {
        return "Change Maven dependency groupId, artifactId and optionally the version or the classifier";
    }

    @Override
    public String getDescription() {
        return "Change the groupId, artifactId and optionally the version or the classifier of a specified Maven dependency.";
    }

    @Override
    protected TreeVisitor<?, ExecutionContext> getVisitor() {
        return new MavenVisitor<ExecutionContext>() {
            @Override
            public Xml visitTag(Xml.Tag tag, ExecutionContext ctx) {
                if (isDependencyTag(oldGroupId, oldArtifactId)) {
                    Optional<Xml.Tag> groupIdTag = tag.getChild("groupId");
                    boolean changed = false;
                    if (groupIdTag.isPresent() && !newGroupId.equals(groupIdTag.get().getValue().orElse(null))) {
                        doAfterVisit(new ChangeTagValueVisitor<>(groupIdTag.get(), newGroupId));
                        changed = true;
                    }
                    Optional<Xml.Tag> artifactIdTag = tag.getChild("artifactId");
                    if (artifactIdTag.isPresent() && !newArtifactId.equals(artifactIdTag.get().getValue().orElse(null))) {
                        doAfterVisit(new ChangeTagValueVisitor<>(artifactIdTag.get(), newArtifactId));
                        changed = true;
                    }
                    if (newVersion != null) {
                        Optional<Xml.Tag> versionTag = tag.getChild("version");
                        if (versionTag.isPresent() && !newVersion.equals(versionTag.get().getValue().orElse(null))) {
                            doAfterVisit(new ChangeTagValueVisitor<>(versionTag.get(), newVersion));
                            changed = true;
                        }
                    }
                    if (newClassifier != null) {
                        Optional<Xml.Tag> classifierTag = tag.getChild("classifier");
                        if (classifierTag.isPresent()) {
                            if (!newClassifier.equals(classifierTag.get().getValue().orElse(null))) {
                                doAfterVisit(new ChangeTagValueVisitor<>(classifierTag.get(), newClassifier));
                                changed = true;
                            }
                        } else {
                            doAfterVisit(new AddToTagVisitor<>(tag, Xml.Tag.build("<classifier>" + newClassifier + "</classifier>")));
                            changed = true;
                        }
                    }
//                    if (changed) {
//                        maybeUpdateModel();
//                    }
                }

                return super.visitTag(tag, ctx);
            }
        };
    }
}
