package fi.uta.cs.weto.db;

import fi.uta.cs.sqldatamodel.*;

/**
 * Generated bean class for ClusterMember.
 * This class extends the database access class by adding bean property getters and setters.
 */
public class BeanClusterMember extends DbClusterMember {

    /**
     * Default constructor.
     */
    public BeanClusterMember() {
        super();
    }

    /**
     * Gets the value of the property clusterId.
     * @return Value as java.lang.Integer.
     */
    public java.lang.Integer getClusterId() {
        return getClusterIdData().getValue();
    }

    /**
     * Sets the value of the property clusterId.
     * @param newValue New value as java.lang.Integer.
     * @throws InvalidValueException if the new value is not valid.
     */
    public void setClusterId( java.lang.Integer newValue ) throws InvalidValueException {
        getClusterIdData().setValue( newValue );
    }

    /**
     * Gets the value of the property userId.
     * @return Value as java.lang.Integer.
     */
    public java.lang.Integer getUserId() {
        return getUserIdData().getValue();
    }

    /**
     * Sets the value of the property userId.
     * @param newValue New value as java.lang.Integer.
     * @throws InvalidValueException if the new value is not valid.
     */
    public void setUserId( java.lang.Integer newValue ) throws InvalidValueException {
        getUserIdData().setValue( newValue );
    }

    /**
     * Gets the value of the property timeStamp.
     * @return Value as java.lang.Integer.
     */
    public java.lang.Integer getTimeStamp() {
        return getTimeStampData().getValue();
    }

    /**
     * Sets the value of the property timeStamp.
     * @param newValue New value as java.lang.Integer.
     * @throws InvalidValueException if the new value is not valid.
     */
    public void setTimeStamp( java.lang.Integer newValue ) throws InvalidValueException {
        getTimeStampData().setValue( newValue );
    }
}

// End of file.
