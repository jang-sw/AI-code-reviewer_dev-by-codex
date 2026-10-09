<%@ page pageEncoding="UTF-8" %>
<%@ include file="/WEB-INF/jsp/fragments/header.jspf" %>
<div class="page-heading"><div><p class="eyebrow">ADMINISTRATION</p><h1>감사 기록</h1><p class="muted">계정, 프로젝트 승인, 리뷰와 이슈 상태 변경의 기록입니다.</p></div></div>
<section class="card">
  <c:choose>
    <c:when test="${empty events}"><div class="empty-state"><c:choose><c:when test="${page > 0}">
      <h2>이 페이지에 표시할 감사 기록이 없습니다</h2><p>첫 페이지나 이전 페이지에서 기록을 확인해 주세요.</p>
      <c:url var="firstAuditPage" value="/admin/audit"><c:param name="page" value="0"/></c:url><a id="audit-first-page" href="<c:out value='${firstAuditPage}'/>">첫 페이지로</a>
    </c:when><c:otherwise><p>아직 기록이 없습니다.</p></c:otherwise></c:choose></div></c:when>
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
