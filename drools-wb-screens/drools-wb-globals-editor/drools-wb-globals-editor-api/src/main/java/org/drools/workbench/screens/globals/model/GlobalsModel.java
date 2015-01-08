package org.drools.workbench.screens.globals.model;

import java.util.ArrayList;
import java.util.List;

import org.drools.workbench.models.datamodel.packages.HasPackageName;
import org.jboss.errai.common.client.api.annotations.Portable;
import org.uberfire.commons.validation.PortablePreconditions;

/**
 * The model for Globals
 */
@Portable
public class GlobalsModel implements HasPackageName {

    private String packageName;

    private List<Global> globals = new ArrayList<Global>();

    public List<Global> getGlobals() {
        return globals;
    }

    public void setGlobals( final List<Global> globals ) {
        this.globals = PortablePreconditions.checkNotNull( "globals",
                                                           globals );
    }

    @Override
    public String getPackageName() {
        return this.packageName;
    }

    @Override
    public void setPackageName( final String packageName ) {
        this.packageName = PortablePreconditions.checkNotNull( "packageName",
                                                               packageName );
    }

    @Override
    public boolean equals( Object o ) {
        if ( this == o ) {
            return true;
        }
        if ( !( o instanceof GlobalsModel ) ) {
            return false;
        }

        GlobalsModel that = (GlobalsModel) o;

        if ( !globals.equals( that.globals ) ) {
            return false;
        }
        if ( packageName != null ? !packageName.equals( that.packageName ) : that.packageName != null ) {
            return false;
        }

        return true;
    }

    @Override
    public int hashCode() {
        int result = packageName != null ? packageName.hashCode() : 0;
        result = 31 * result + globals.hashCode();
        return result;
    }

}
