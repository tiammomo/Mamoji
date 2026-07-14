package com.mamoji.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SingleInstanceDatabaseGuardTest {
    private static final String ACQUIRE_SQL = "SELECT pg_try_advisory_lock(?)";
    private static final String RELEASE_SQL = "SELECT pg_advisory_unlock(?)";

    @Test
    void disabledGuardDoesNotOpenDatabaseConnection() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        SingleInstanceDatabaseGuard guard = new SingleInstanceDatabaseGuard(dataSource, false);

        guard.acquire();
        guard.release();

        verify(dataSource, never()).getConnection();
    }

    @Test
    void holdsSuccessfulLeaseUntilReleaseThenUnlocksAndClosesConnection() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement acquireStatement = mock(PreparedStatement.class);
        PreparedStatement releaseStatement = mock(PreparedStatement.class);
        ResultSet acquireResult = mock(ResultSet.class);
        ResultSet releaseResult = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(ACQUIRE_SQL)).thenReturn(acquireStatement);
        when(acquireStatement.executeQuery()).thenReturn(acquireResult);
        when(acquireResult.next()).thenReturn(true);
        when(acquireResult.getBoolean(1)).thenReturn(true);
        when(connection.prepareStatement(RELEASE_SQL)).thenReturn(releaseStatement);
        when(releaseStatement.executeQuery()).thenReturn(releaseResult);
        SingleInstanceDatabaseGuard guard = new SingleInstanceDatabaseGuard(dataSource, true);

        guard.acquire();

        verify(connection, never()).close();
        verify(acquireResult).close();
        verify(acquireStatement).close();

        guard.release();

        ArgumentCaptor<Long> acquireKey = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Long> releaseKey = ArgumentCaptor.forClass(Long.class);
        verify(acquireStatement).setLong(org.mockito.ArgumentMatchers.eq(1), acquireKey.capture());
        verify(releaseStatement).setLong(org.mockito.ArgumentMatchers.eq(1), releaseKey.capture());
        assertEquals(acquireKey.getValue(), releaseKey.getValue());
        verify(releaseStatement).executeQuery();
        verify(releaseResult).close();
        verify(releaseStatement).close();
        verify(connection).close();
    }

    @Test
    void occupiedLeaseFailsStartupAndClosesConnection() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet result = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(ACQUIRE_SQL)).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(result);
        when(result.next()).thenReturn(true);
        when(result.getBoolean(1)).thenReturn(false);
        SingleInstanceDatabaseGuard guard = new SingleInstanceDatabaseGuard(dataSource, true);

        IllegalStateException exception = assertThrows(IllegalStateException.class, guard::acquire);

        assertTrue(exception.getMessage().contains("Another Mamoji backend already holds the database lease"));
        verify(result).close();
        verify(statement).close();
        verify(connection).close();
        guard.release();
        verify(connection).close();
    }
}
