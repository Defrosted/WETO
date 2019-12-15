package fi.uta.cs.weto.db;

import java.sql.*;
import java.util.Iterator;
import fi.uta.cs.sqldatamodel.*;
import fi.uta.cs.sqldatatypes.*;

/**
 * Generated database access class for view StudentView.
 * 
 */
public class DbStudentView extends SqlAssignableObject implements Cloneable {
    private SqlInteger taskIdData;
    private SqlLongvarchar emailData;
    private SqlInteger userIdData;
    private SqlLongvarchar studentNumberData;
    private SqlLongvarchar loginNameData;
    private SqlLongvarchar firstNameData;
    private SqlLongvarchar lastNameData;

    /**
     * Default constructor.
     */
    public DbStudentView() {
        super();
        taskIdData = new SqlInteger();
        emailData = new SqlLongvarchar();
        userIdData = new SqlInteger();
        studentNumberData = new SqlLongvarchar();
        loginNameData = new SqlLongvarchar();
        firstNameData = new SqlLongvarchar();
        lastNameData = new SqlLongvarchar();
    }

    /**
     * Updates the data from the given ResultSet object.
     * @param resultSet ResultSet object containing the data.
     * @param baseIndex Base index of the columns in the ResultSet (exclusive).
     * @throws SQLException if the JDBC operation fails.
     * @throws InvalidValueException if the attributes are invalid.
     */
    public void setFromResultSet(ResultSet resultSet, int baseIndex) throws SQLException, InvalidValueException {
        taskIdData.jdbcSetValue( (java.lang.Integer) resultSet.getObject(baseIndex+1) );
        emailData.jdbcSetValue( (java.lang.String) resultSet.getObject(baseIndex+2) );
        userIdData.jdbcSetValue( (java.lang.Integer) resultSet.getObject(baseIndex+3) );
        studentNumberData.jdbcSetValue( (java.lang.String) resultSet.getObject(baseIndex+4) );
        loginNameData.jdbcSetValue( (java.lang.String) resultSet.getObject(baseIndex+5) );
        firstNameData.jdbcSetValue( (java.lang.String) resultSet.getObject(baseIndex+6) );
        lastNameData.jdbcSetValue( (java.lang.String) resultSet.getObject(baseIndex+7) );
    }

    public String toString() {
        StringBuffer sb = new StringBuffer();
        sb.append("StudentView\n");
        sb.append("taskId:" +  taskIdData.toString() + "\n");
        sb.append("email:" +  emailData.toString() + "\n");
        sb.append("userId:" +  userIdData.toString() + "\n");
        sb.append("studentNumber:" +  studentNumberData.toString() + "\n");
        sb.append("loginName:" +  loginNameData.toString() + "\n");
        sb.append("firstName:" +  firstNameData.toString() + "\n");
        sb.append("lastName:" +  lastNameData.toString() + "\n");
        sb.append("\n");
        return(sb.toString());
    }

    public boolean equals( Object obj ) {
        if( obj == null ) return false;
        if( !(obj instanceof DbStudentView) ) return false;
        DbStudentView dbObj = (DbStudentView)obj;
        if( !taskIdData.equals( dbObj.taskIdData ) ) return false;
        if( !emailData.equals( dbObj.emailData ) ) return false;
        if( !userIdData.equals( dbObj.userIdData ) ) return false;
        if( !studentNumberData.equals( dbObj.studentNumberData ) ) return false;
        if( !loginNameData.equals( dbObj.loginNameData ) ) return false;
        if( !firstNameData.equals( dbObj.firstNameData ) ) return false;
        if( !lastNameData.equals( dbObj.lastNameData ) ) return false;
        return true;
    }

    public Object clone() throws CloneNotSupportedException {
        return super.clone();
    }

    /**
     * Gets the raw data object for taskId attribute.
     * @return Data object as SqlInteger.
     */
    public SqlInteger getTaskIdData() {
        return taskIdData;
    }

    /**
     * Gets the raw data object for email attribute.
     * @return Data object as SqlLongvarchar.
     */
    public SqlLongvarchar getEmailData() {
        return emailData;
    }

    /**
     * Gets the raw data object for userId attribute.
     * @return Data object as SqlInteger.
     */
    public SqlInteger getUserIdData() {
        return userIdData;
    }

    /**
     * Gets the raw data object for studentNumber attribute.
     * @return Data object as SqlLongvarchar.
     */
    public SqlLongvarchar getStudentNumberData() {
        return studentNumberData;
    }

    /**
     * Gets the raw data object for loginName attribute.
     * @return Data object as SqlLongvarchar.
     */
    public SqlLongvarchar getLoginNameData() {
        return loginNameData;
    }

    /**
     * Gets the raw data object for firstName attribute.
     * @return Data object as SqlLongvarchar.
     */
    public SqlLongvarchar getFirstNameData() {
        return firstNameData;
    }

    /**
     * Gets the raw data object for lastName attribute.
     * @return Data object as SqlLongvarchar.
     */
    public SqlLongvarchar getLastNameData() {
        return lastNameData;
    }

    /**
     * Selects the row of this object from the database view StudentView and updates the attributes accordingly.
     * @param con Open and active connection to the database.
     * @throws SQLException if the JDBC operation fails.
     * @throws InvalidValueException if the attributes are invalid.
     * @throws NoSuchItemException if the row to be selected does not exist or is not unique.
     */
    public void select(Connection con) throws SQLException, InvalidValueException, NoSuchItemException {
        String prepareString = "select * from StudentView where taskId = ? AND email = ? AND userId = ? AND studentNumber = ? AND loginName = ? AND firstName = ? AND lastName = ?";
        PreparedStatement ps = con.prepareStatement(prepareString);
        ps.setObject(1, taskIdData.jdbcGetValue());
        ps.setObject(2, emailData.jdbcGetValue());
        ps.setObject(3, userIdData.jdbcGetValue());
        ps.setObject(4, studentNumberData.jdbcGetValue());
        ps.setObject(5, loginNameData.jdbcGetValue());
        ps.setObject(6, firstNameData.jdbcGetValue());
        ps.setObject(7, lastNameData.jdbcGetValue());
        ResultSet rs = ps.executeQuery();
        if (rs.next()) {
            setFromResultSet(rs,0);
            rs.close();
            ps.close();
        } else {
            rs.close();
            ps.close();
            throw new NoSuchItemException();
        }
    }

    /**
     * Constructs and returns a selection iterator for rows in database view StudentView.
     * 
     * @param con Open and active connection to the database.
     * @param whereClause Optional where clause. If null is given, all the rows are selected.
     * 
     * @return Newly constructed iterator that returns objects of type StudentView. The iterator is closed when hasNext() returns false or the iterator is finalized.
     * 
     * @throws SQLException if the JDBC operation fails.
     * 
     * Note that the iterator may throw SqlSelectionIteratorException (which is a runtime exception) when its methods are called.
     */
    public static Iterator selectionIterator( Connection con, String whereClause ) throws SQLException {
        String prepareString;
        if( whereClause == null ) whereClause = "";
        if( whereClause.equals("") )
            prepareString = "select * from StudentView";
        else
            prepareString = "select * from StudentView where " + whereClause;
        PreparedStatement ps = con.prepareStatement(prepareString);
        return new SqlSelectionIterator( ps, StudentView.class );
    }
}

// End of file.
