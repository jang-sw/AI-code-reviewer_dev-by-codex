<%@ tag pageEncoding="UTF-8" body-content="empty" %>
<%@ attribute name="value" required="true" type="java.lang.String" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<c:choose>
  <c:when test="${value == 'PENDING'}"><span class="badge badge-warning">승인 대기</span></c:when>
  <c:when test="${value == 'APPROVED'}"><span class="badge badge-success">승인 완료</span></c:when>
  <c:when test="${value == 'REJECTED'}"><span class="badge badge-danger">반려</span></c:when>
  <c:when test="${value == 'PAUSED'}"><span class="badge badge-warning">일시 중지</span></c:when>
  <c:when test="${value == 'RUNNING'}"><span class="badge badge-warning">리뷰 중</span></c:when>
  <c:when test="${value == 'SUCCEEDED'}"><span class="badge badge-success">처리 완료</span></c:when>
  <c:when test="${value == 'FAILED'}"><span class="badge badge-danger">확인 필요</span></c:when>
  <c:when test="${value == 'OPEN'}"><span class="badge badge-warning">미처리</span></c:when>
  <c:when test="${value == 'RESOLVED'}"><span class="badge badge-success">해결</span></c:when>
  <c:when test="${value == 'DISMISSED'}"><span class="badge">검토 제외</span></c:when>
  <c:when test="${value == 'CRITICAL'}"><span class="badge badge-danger">긴급</span></c:when>
  <c:when test="${value == 'HIGH'}"><span class="badge badge-danger">높음</span></c:when>
  <c:when test="${value == 'MEDIUM'}"><span class="badge badge-warning">보통</span></c:when>
  <c:when test="${value == 'LOW'}"><span class="badge">낮음</span></c:when>
  <c:otherwise><span class="badge">미실행</span></c:otherwise>
</c:choose>
