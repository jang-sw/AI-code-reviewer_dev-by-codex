<%@ page pageEncoding="UTF-8" %>
<%@ include file="/WEB-INF/jsp/fragments/header.jspf" %>
<div class="page-heading"><div><p class="eyebrow">ADMINISTRATION</p><h1>감사 기록</h1><p class="muted">계정, 프로젝트 승인, 리뷰와 이슈 상태 변경의 기록입니다.</p></div></div>
<section class="card">
  <c:choose>
    <c:when test="${empty events}"><p class="empty-state">아직 기록이 없습니다.</p></c:when>
    <c:otherwise>
      <div class="table-wrap"><table>
        <thead><tr><th scope="col">발생 시각</th><th scope="col">작업자</th><th scope="col">작업</th><th scope="col">대상</th><th scope="col">내용</th></tr></thead>
        <tbody><c:forEach var="event" items="${events}"><tr>
          <td><c:out value="${event.created_at}"/></td>
          <td><c:out value="${event.username}" default="시스템"/></td>
          <td><code><c:out value="${event.action}"/></code></td>
          <td><c:out value="${event.target_type}"/> #<c:out value="${event.target_id}"/></td>
          <td><c:out value="${event.detail}"/></td>
        </tr></c:forEach></tbody>
      </table></div>
    </c:otherwise>
  </c:choose>
</section>
<nav aria-label="감사 기록 페이지">
  <c:if test="${page > 0}"><a href="<c:url value='/admin/audit'><c:param name='page' value='${page - 1}'/></c:url>">이전</a></c:if>
  <span><c:out value="${page + 1}"/> 페이지</span>
  <c:if test="${hasNext}"><a href="<c:url value='/admin/audit'><c:param name='page' value='${page + 1}'/></c:url>">다음</a></c:if>
</nav>
<%@ include file="/WEB-INF/jsp/fragments/footer.jspf" %>
