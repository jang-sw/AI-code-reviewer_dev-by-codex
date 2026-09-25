package com.aicreviewer.review;

import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

class PostgresProjectReviewLockTest {
    @Test
    void holdsDedicatedSessionUntilCloseThenUnlocksExactlyOnce() throws Exception {
        DataSource source = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement acquire = mock(PreparedStatement.class);
        PreparedStatement release = mock(PreparedStatement.class);
        ResultSet acquired = result(true);
        ResultSet released = result(true);
        when(source.getConnection()).thenReturn(connection);
        when(connection.prepareStatement("select pg_try_advisory_lock(?)")).thenReturn(acquire);
        when(connection.prepareStatement("select pg_advisory_unlock(?)")).thenReturn(release);
        when(acquire.executeQuery()).thenReturn(acquired);
        when(release.executeQuery()).thenReturn(released);

        var lease = new PostgresProjectReviewLock(source, 3).tryAcquire(52).orElseThrow();
        verify(acquire).setQueryTimeout(3);
        verify(connection, never()).close();
        verify(connection, never()).prepareStatement("select pg_advisory_unlock(?)");
        lease.close();
        lease.close();

        var order = inOrder(release, connection);
        order.verify(release).setQueryTimeout(3);
        order.verify(release).setLong(eq(1), anyLong());
        order.verify(release).executeQuery();
        order.verify(connection).close();
        verify(connection, times(1)).close();
        verify(connection, never()).abort(any());
    }

    @Test
    void contendedLockClosesUnusedSession() throws Exception {
        DataSource source = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement acquire = mock(PreparedStatement.class);
        ResultSet acquired = result(false);
        when(source.getConnection()).thenReturn(connection);
        when(connection.prepareStatement("select pg_try_advisory_lock(?)")).thenReturn(acquire);
        when(acquire.executeQuery()).thenReturn(acquired);

        assertThat(new PostgresProjectReviewLock(source).tryAcquire(52)).isEmpty();
        verify(connection).close();
        verify(connection, never()).prepareStatement("select pg_advisory_unlock(?)");
    }

    @Test
    void uncertainAcquisitionDiscardsPhysicalSessionInsteadOfLeakingPoolLock() throws Exception {
        DataSource source = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement acquire = mock(PreparedStatement.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.prepareStatement("select pg_try_advisory_lock(?)")).thenReturn(acquire);
        when(acquire.executeQuery()).thenThrow(new SQLException("Connection lost after server acquired lock"));

        assertThatThrownBy(() -> new PostgresProjectReviewLock(source).tryAcquire(52)).isInstanceOf(IllegalStateException.class);
        var order = inOrder(connection);
        order.verify(connection).abort(any());
        order.verify(connection).close();
    }

    @Test
    void unlockFailureAbortsSessionBeforeReturningConnection() throws Exception {
        DataSource source = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement acquire = mock(PreparedStatement.class);
        PreparedStatement release = mock(PreparedStatement.class);
        ResultSet acquired = result(true);
        when(source.getConnection()).thenReturn(connection);
        when(connection.prepareStatement("select pg_try_advisory_lock(?)")).thenReturn(acquire);
        when(connection.prepareStatement("select pg_advisory_unlock(?)")).thenReturn(release);
        when(acquire.executeQuery()).thenReturn(acquired);
        when(release.executeQuery()).thenThrow(new SQLException("Lost session"));

        var lease = new PostgresProjectReviewLock(source).tryAcquire(52).orElseThrow();
        assertThatThrownBy(lease::close).isInstanceOf(IllegalStateException.class);
        var order = inOrder(connection);
        order.verify(connection).abort(any());
        order.verify(connection).close();
    }

    @Test
    void timeoutConfigurationFailureDiscardsSessionWithoutSendingLockQuery() throws Exception {
        DataSource source = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement acquire = mock(PreparedStatement.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.prepareStatement("select pg_try_advisory_lock(?)")).thenReturn(acquire);
        doThrow(new SQLException("Timeout setting failed")).when(acquire).setQueryTimeout(2);

        assertThatThrownBy(() -> new PostgresProjectReviewLock(source, 2).tryAcquire(52))
                .isInstanceOf(IllegalStateException.class);
        verify(acquire, never()).executeQuery();
        var order = inOrder(connection);
        order.verify(connection).abort(any());
        order.verify(connection).close();
    }

    @Test
    void disabledOrUnboundedQueryTimeoutCannotStart() {
        DataSource source = mock(DataSource.class);
        for (int seconds : new int[] { -1, 0, 3601 }) {
            assertThatThrownBy(() -> new PostgresProjectReviewLock(source, seconds))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        verifyNoInteractions(source);
    }

    private static ResultSet result(boolean value) throws SQLException {
        ResultSet result = mock(ResultSet.class);
        when(result.next()).thenReturn(true);
        when(result.getBoolean(1)).thenReturn(value);
        return result;
    }
}
