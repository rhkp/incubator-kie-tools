/*
 * Copyright 2012 JBoss Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.drools.workbench.screens.categories.client;

import javax.enterprise.context.Dependent;
import javax.inject.Inject;

import com.google.gwt.user.client.ui.IsWidget;
import org.drools.workbench.screens.categories.client.resources.i18n.Constants;
import org.drools.workbench.screens.categories.client.type.CategoryDefinitionResourceType;
import org.guvnor.common.services.shared.metadata.CategoriesService;
import org.guvnor.common.services.shared.metadata.model.CategoriesModelContent;
import org.guvnor.common.services.shared.metadata.model.Metadata;
import org.jboss.errai.common.client.api.Caller;
import org.jboss.errai.common.client.api.RemoteCallback;
import org.kie.uberfire.client.callbacks.HasBusyIndicatorDefaultErrorCallback;
import org.kie.workbench.common.widgets.client.popups.file.CommandWithCommitMessage;
import org.kie.workbench.common.widgets.client.popups.file.SaveOperationService;
import org.kie.workbench.common.widgets.client.resources.i18n.CommonConstants;
import org.kie.workbench.common.widgets.metadata.client.KieEditor;
import org.uberfire.backend.vfs.ObservablePath;
import org.uberfire.client.annotations.WorkbenchEditor;
import org.uberfire.client.annotations.WorkbenchMenu;
import org.uberfire.client.annotations.WorkbenchPartTitle;
import org.uberfire.client.annotations.WorkbenchPartView;
import org.uberfire.lifecycle.IsDirty;
import org.uberfire.lifecycle.OnClose;
import org.uberfire.lifecycle.OnMayClose;
import org.uberfire.lifecycle.OnStartup;
import org.uberfire.mvp.Command;
import org.uberfire.mvp.PlaceRequest;
import org.uberfire.workbench.model.menu.Menus;
import org.uberfire.workbench.type.FileNameUtil;

/**
 *
 */
@Dependent
@WorkbenchEditor(identifier = "CategoryFileManager", supportedTypes = {CategoryDefinitionResourceType.class})
public class CategoriesEditorPresenter
        extends KieEditor {

    private final CategoriesEditorView view;

    @Inject
    private Caller<CategoriesService> categoryService;

    @Inject
    private CategoryDefinitionResourceType type;
    private Metadata metadata;

    @Inject
    public CategoriesEditorPresenter(CategoriesEditorView baseView) {
        super(baseView);
        view = baseView;
    }

    @OnStartup
    public void onStartup(
            final ObservablePath path,
            final PlaceRequest place) {
        super.init(path, place);
    }

    @Override protected Command onValidate() {
        return null;
    }

    @Override protected void loadContent() {
        categoryService.call(getModelSuccessCallback(),
                new HasBusyIndicatorDefaultErrorCallback(view)).getContentByRoot(versionRecordManager.getCurrentPath());

    }

    @Override protected void save() {

        new SaveOperationService().save(versionRecordManager.getCurrentPath(),
                new CommandWithCommitMessage() {
                    @Override
                    public void execute(final String commitMessage) {
                        view.showBusyIndicator(CommonConstants.INSTANCE.Saving());
                        categoryService.call(getSaveSuccessCallback(),
                                new HasBusyIndicatorDefaultErrorCallback(view)).save(
                                versionRecordManager.getCurrentPath(),
                                view.getContent(),
                                metadata,
                                commitMessage
                        );
                    }
                });
    }

    private RemoteCallback<CategoriesModelContent> getModelSuccessCallback() {
        return new RemoteCallback<CategoriesModelContent>() {

            @Override
            public void callback(final CategoriesModelContent content) {
                resetEditorPages(content.getOverview());

                view.setContent(content.getCategories());
                view.hideBusyIndicator();

            }
        };
    }

    @IsDirty
    public boolean isDirty() {
        return view.isDirty();
    }

    @OnClose
    public void onClose() {
        this.versionRecordManager.clear();
    }

    @OnMayClose
    public boolean checkIfDirty() {
        if (isDirty()) {
            return view.confirmClose();
        }
        return true;
    }

    @WorkbenchPartTitle
    public String getTitle() {
        return Constants.INSTANCE.CategoriesEditor() + " [" + FileNameUtil.removeExtension(versionRecordManager.getCurrentPath(),
                type) + "]";
    }

    @WorkbenchPartView
    public IsWidget getWidget() {
        return super.getWidget();
    }

    @WorkbenchMenu
    public Menus getMenus() {
        return menus;
    }

}
