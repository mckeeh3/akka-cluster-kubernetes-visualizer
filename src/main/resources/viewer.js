var webSocket;

function sendWebSocketRequest(request) {
  if (webSocket && webSocket.readyState == WebSocket.OPEN) {
    webSocket.send(request);
  } else {
    webSocket = new WebSocket('ws://' + location.host + '/viewer-entities');
    update({ 'clientActivities': [], 'serverActivities': [], 'tree': { 'name': 'cluster', 'type': 'cluster' }});

    webSocket.onopen = function(event) {
      console.log('WebSocket connected', event);
      webSocket.send(request);
    }

    webSocket.onmessage = function(event) {
      console.log(event);
      root = JSON.parse(event.data);
      update(root);
    }

    webSocket.onerror = function(error) {
      console.error('WebSocket error', error);
    }

    webSocket.onclose = function(event) {
      console.log('WebSocket close', event);
    }
  }
}

const chartDiv = document.getElementById('chart');
const width = chartDiv.clientWidth;
const height = chartDiv.clientHeight;
const radius = Math.min(width, height) / 2;
const tree = d3.tree().size([2 * Math.PI, radius - 75]);

const grid = Math.min(width, height) / 60;
const margin = grid * 0.1;
const widthId = grid * 1.5;
const widthIp = grid * 5;
const widthCount = grid * 4;

const messageCountLast = { count: 0, time: new Date() };

const svg = d3.select('svg')
  .style('width', width)
  .style('height', height)
  .style('padding', '0px')
  .style('box-sizing', 'border-box')
  .style('font', 'sans-serif');

svg.append('rect')
    .attr('width', '100%')
    .attr('height', '100%')
    .attr('fill', '#001017');

const g = svg.append('g')
  .attr('transform', 'translate(' + width / 2 + ',' + height / 2 + ')');

const gServers = g.append('g')
  .attr('class', 'httpServers')

const gClients = g.append('g')
  .attr('class', 'httpClients')

const gHttpServerLink = g.append('g')
  .attr('class', 'http-server-link')
  .attr('stroke-opacity', '0.4');

const gHttpClientLink = g.append('g')
  .attr('class', 'http-client-link')
  .attr('stroke-opacity', '0.4');

const gLink = g.append('g')
  .attr('class', 'links')
  .attr('fill', 'none')
  .attr('stroke', '#555')
  .attr('stroke-opacity', '0.4')
  .attr('stroke-width', 1.5);

const gNode = g.append('g')
  .attr('class', 'nodes')
  .attr('stroke-linejoin', 'round')
  .attr('stroke-width', 3);

const gStatistics = g.append('g')
  .attr('class', 'statistics')

sendWebSocketRequest();
setInterval(sendWebSocketRequest, 5000);

function update(data) {
  updateHttpClientView(data.clientActivities);
  updateHttpServerView(data.serverActivities);

  const shardingData = tree(d3.hierarchy(data.tree));

  updateHttpClientLinks(data.clientActivities, shardingData.links());
  updateHttpServerLinks(data.serverActivities, shardingData.links());
  updateStatistics(data, shardingData.links());

  updateCropCircle(shardingData);
}

function updateCropCircle(root) {
  const t1 = d3.transition()
    .duration(750);

  const t2 = d3.transition()
    .delay(750)
    .duration(750);

  const t3 = d3.transition()
    .delay(1500)
    .duration(750);

  const link = gLink.selectAll('path.link')
    .data(root.links(), linkId);

  const linkEnter = link.enter().append('path')
    .attr('id', linkId)
    .attr('class', d => 'link ' + d.source.data.type)
    .style('opacity', 0.000001)
    .attr('d', d3.linkRadial()
                 .angle(d => d.x)
                 .radius(d => d.y));

  link.transition(t2)
    .style('opacity', 1.0)
    .attr('d', d3.linkRadial()
                 .angle(d => d.x)
                 .radius(d => d.y));

  linkEnter.transition(t3)
    .style('opacity', 1.0);

  link.exit()
    .transition(t1)
    .style('opacity', 0.000001)
    .remove();

  const node = gNode.selectAll('g')
    .data(root.descendants(), nodeId);

  const nodeEnter = node.enter().append('g')
    .attr('id', nodeId)
    .attr('class', d => 'node ' + d.data.type)
    .attr('transform', d => `rotate(${d.x * 180 / Math.PI - 90}) translate(${d.y},0)`)
    .on('mouseover', function() {
      d3.select(this).select('text').style('font-size', 24).style('fill', '#FEE1B7');
    })
    .on('mouseout', function(d) {
      d3.select(this).select('text').style('font-size', 12).style('fill', '#999');
    });

  nodeEnter.append('circle')
    .attr('class', d => d.data.type)
    .attr('fill', circleColor)
    .attr('r', circleRadius)
    .attr('cursor', 'pointer')
    .on('click', clickCircle)
    .style('opacity', 0.000001);

  nodeEnter.append('text')
    .attr('dy', '0.31em')
    .attr('x', labelOffsetX)
    .attr('text-anchor', d => d.x < Math.PI === !d.children ? 'start' : 'end')
    .attr('transform', d => d.x >= Math.PI ? 'rotate(180)' : null)
    .style('opacity', 0.000001)
    .text(d => d.data.name);

  nodeEnter.filter(d => d.data.type.includes('member'))
    .append('text')
    .attr('class', 'member')
    .attr('dy', '0.31em')
    .attr('transform', d => d.x >= Math.PI ? 'rotate(180)' : null)
    .attr('cursor', 'pointer')
    .attr('text-anchor', 'middle')
    .on('click', clickCircle)
    .style('font-size', 22)
    .style('fill', '#FFF')
    .style('opacity', 0.000001)
    .text(memberNumber);

  nodeEnter.append('title')
    .text(d => d.data.type);

  node.transition(t2)
    .attr('transform', d => `rotate(${d.x * 180 / Math.PI - 90}) translate(${d.y},0)`)
    .select('circle.entity')
      .attr('r', circleRadius)
      .style('fill', entityColor)
      .style('opacity', 1.0);

  node.transition(t2)
    .select('circle.shard')
      .attr('r', circleRadius)
      .style('fill', shardColor)
      .style('opacity', 1.0);

  node.transition(t2)
    .select('circle.member')
      .attr('r', circleRadius)
      .style('fill', circleColor)
      .style('opacity', 1.0);

  node.transition(t2)
    .select('text')
      .style('opacity', 1.0);

  node.transition(t2)
    .select('text.member')
      .style('opacity', 1.0);

  nodeEnter.transition(t3)
    .select('circle')
      .style('opacity', 1.0);

  nodeEnter.transition(t3)
    .select('text')
      .style('opacity', 1.0);

  nodeEnter.transition(t3)
    .select('text.member')
      .style('opacity', 1.0);

  node.exit()
    .transition(t1)
    .select('circle')
      .attr('r', circleRadiusExit)
      .style('opacity', 0.000001)
      .style('fill', 'red');

  node.exit()
    .transition(t1)
    .select('text')
      .style('opacity', 0.000001);

  node.exit()
    .transition(t1)
    .select('text.member')
      .style('opacity', 0.000001);

  node.exit()
    .transition(t1)
    .remove();
}

function updateHttpClientView(data) {
  const clients = gClients.selectAll('g')
    .data(clientData(data));

  updateHttpNodeView(clients);

  function clientData(data) {
    const nodes = [];

    const x = width / 2 - (grid + widthId + margin + widthIp + margin + widthCount);
    data.forEach((n, i) => {
      const y = grid + i * (grid + margin) - height / 2;
      nodes.push({ x: x, y: y, ip: n.client.ip, id: n.client.id, messageCount: n.messageCount.toLocaleString(), active: true });
    });
    return nodes;
  }
}

function updateHttpServerView(data) {
  const servers = gServers.selectAll('g')
    .data(serverData(data));
 
  updateHttpNodeView(servers);

  function serverData(data) {
    const nodes = [];

    const x = grid - width / 2;
    data.forEach((n, i) => {
      const y = grid + i * (grid + margin) - height / 2;
      nodes.push({ x: x, y: y, ip: n.server.ip, id: n.server.id, messageCount: n.messageCount.toLocaleString(), active: true });
    });
    return nodes;
  }
}

function updateHttpNodeView(nodes) {
  const bgColor = 'rgba(255, 255, 255, 0.1)';
  const txColor = '#FFF';
  const nodesEnter = nodes.enter().append('g')
    .attr('cursor', 'pointer')
    .on('click', clickMember);

  nodesEnter.append('rect')
    .attr('x', d => d.x)
    .attr('y', d => d.y)
    .attr('width', widthId)
    .attr('height', grid)
    .style('fill', d => d.active ? bgColor : '#555');

  nodesEnter.append('text')
    .attr('x', d => d.x + widthId - margin)
    .attr('y', d => d.y + grid - margin)
    .attr('text-anchor', 'end')
    .attr('class', 'id')
    .style('font-size', grid - margin * 2.5)
    .style('fill', txColor)
    .text(d => d.id);

  nodesEnter.append('rect')
    .attr('x', d => d.x + widthId + margin)
    .attr('y', d => d.y)
    .attr('width', widthIp)
    .attr('height', grid)
    .style('fill', d => d.active ? bgColor : '#555');

  nodesEnter.append('text')
    .attr('x', d => d.x + widthId + margin + widthIp - margin)
    .attr('y', d => d.y + grid - margin)
    .attr('text-anchor', 'end')
    .attr('class', 'ip')
    .style('font-size', grid - margin * 2.5)
    .style('fill', txColor)
    .text(d => d.ip);

  nodesEnter.append('rect')
    .attr('x', d => d.x + widthId + margin + widthIp + margin)
    .attr('y', d => d.y)
    .attr('width', widthCount)
    .attr('height', grid)
    .style('fill', d => d.active ? bgColor : '#555');

  nodesEnter.append('text')
    .attr('x', d => d.x + widthId + margin + widthIp + margin + widthCount - margin)
    .attr('y', d => d.y + grid - margin)
    .attr('text-anchor', 'end')
    .attr('class', 'messageCount')
    .style('font-size', grid - margin * 2.5)
    .style('fill', txColor)
    .text(d => d.messageCount);

  nodes.select('rect')
    .style('fill', d => d.active ? bgColor : '#555');

  nodes.select('text.id')
    .text(d => d.id);

  nodes.select('text.ip')
    .text(d => d.ip);

  nodes.select('text.messageCount')
    .text(d => d.messageCount);

  nodes.exit()
    .remove();
}

function updateHttpClientLinks(data, shardingLinks) {
  const links = linksDeDup(clientsLinks(data, shardingLinks));

  const t1 = d3.transition()
    .duration(750);

  const t2 = d3.transition()
    .delay(750)
    .duration(750);

  const t3 = d3.transition()
    .delay(1500)
    .duration(750);

  const link = gHttpClientLink.selectAll('path.http-client')
    .data(links, d => d.source.id + '-' + d.target.id);

  const linkEnter = link.enter().append('path')
    .attr('id', function (d) { 
                       return d.source.id + '-' + d.target.id; })
    .attr('class', d => 'http-client http-client-id-' + d.source.id)
    .attr('stroke', d => d3.schemeSet3[Number(d.source.id) % d3.schemeSet3.length])
    .style('opacity', 0.000001)
    .attr("d", d3.linkHorizontal()
              .x(d => d.x)
              .y(d => d.y));

  link.transition(t2)
    .style('opacity', 1.0)
    .attr("d", d3.linkHorizontal()
              .x(d => d.x)
              .y(d => d.y));

  linkEnter.transition(t3)
    .style('opacity', 1.0);

  link.exit()
    .transition(t1)
    .style('opacity', 0.000001)
    .remove();

  function clientsLinks(data, shardingLinks) {
    const links = [];
    const x = width / 2 - (grid + widthId + margin + widthIp + margin + widthCount);
    data.forEach((c, i) => {
      const id = c.client.id;
      const y = (grid + grid / 2 + i * (grid + margin)) - height / 2;
      const clientLink = { x: x, y: y };
      links.push(...clientLinks(id, clientLink, c.links, shardingLinks));
    });
    return links;
  }

  function clientLinks(sourceId, source, clientLinks, shardingLinks) {
    const links = [];

    clientLinks.forEach(l => {
      const serverId = l.server.id;
      const serverIp = l.server.ip;
      const serverLink = shardingLinks.find(l => l.target.data.name.includes(serverIp));
      if (serverLink && showServerLinks(serverIp)) {
        const targetX = serverLink.target.y * Math.sin(serverLink.target.x);
        const targetY = 0 - serverLink.target.y * Math.cos(serverLink.target.x);
        links.push({ source: { id: sourceId, x: source.x, y: source.y },
                     target: { id: serverId, x: targetX, y: targetY } });
      }
    });
    return links;
  }

  function linksDeDup(links) {
    return links.reduce((a, c) => {
      const x = a.find(l => l.source.id + '-' + l.target.id === c.source.id + '-' + c.target.id);
      if (x) {
        return a;
      } else {
        return a.concat([c]);
      }
    }, []);
  }
}

function updateHttpServerLinks(data, shardingLinks) {
  const links = serversLinks(data, shardingLinks);

  const t1 = d3.transition()
    .duration(750);

  const t2 = d3.transition()
    .delay(750)
    .duration(750);

  const t3 = d3.transition()
    .delay(1500)
    .duration(750);

  const link = gHttpServerLink.selectAll('path.http-server')
    .data(links, d => d.source.id + '-' + d.target.id);

  const linkEnter = link.enter().append('path')
    .attr('id', function (d) { 
                       return d.source.id + '-' + d.target.id; })
    .attr('class', d => 'http-server http-server-id-' + d.source.id)
    .attr('stroke', d => d3.schemeSet3[Number(d.source.id) % d3.schemeSet3.length])
    .style('opacity', 0.000001)
    .attr('d', d3.linkRadial()
                 .angle(d => d.x)
                 .radius(d => d.y));

  link.transition(t2)
    .style('opacity', 1.0)
    .attr('d', d3.linkRadial()
                 .angle(d => d.x)
                 .radius(d => d.y));

  linkEnter.transition(t3)
    .style('opacity', 1.0);

  link.exit()
    .transition(t1)
    .style('opacity', 0.000001)
    .remove();

  function serversLinks(data, shardingLinks) {
    const links = [];
    data.forEach(s => {
      const ip = s.server.ip;
      const id = s.server.id;
      const serverLink = shardingLinks.find(l => l.target.data.name.includes(ip));
      if (serverLink && showServerLinks(ip)) {
        links.push(...serverLinks(id, serverLink.target, s.links, shardingLinks));
      }
    });
    return links;
  }

  function serverLinks(sourceId, source, serverLinks, shardingLinks) {
    const links = [];
    serverLinks.forEach(l => {
      const entityId = l.entityId;
      const entityLink = shardingLinks.find(l => l.target.data.name == entityId);
      if (entityLink) {
        links.push({ source: { id: sourceId, x: source.x, y: source.y }, 
                     target: { id: entityId, x: entityLink.target.x, y: entityLink.target.y } });
      }
    });
    return links;
  }
}

function updateStatistics(data, shardingDataLinks) {
  const bgColor = 'rgba(255, 255, 255, 0.1)';
  const txColor = '#FFF';
  const entityCount = shardingDataLinks.reduce((a, c) => a + (c.target.data.type == 'entity' ? 1 : 0), 0);
  const messageCount = data.serverActivities.reduce((a, c) => a + c.messageCount, 0);
  const messageCountDelta = messageCount - messageCountLast.count;
  const timeDeltaSeconds = Math.round((new Date() - messageCountLast.time) / 1000);
  const messageRatePerSecond = Math.round(messageCountDelta / timeDeltaSeconds);

  messageCountLast.count = messageCount;
  messageCountLast.time = new Date();

  console.log(entityCount, messageRatePerSecond);

  const x = grid - width / 2;
  const y = height / 2 - grid - 3 * (grid + margin);
  const widthLabel = grid * 6;
  const widthValue = grid * 5;
  const labelsValues = [];

  if (entityCount > 0) {
    labelsValues.push({ x: x, y: y, label: 'Entity count', value: entityCount.toLocaleString() });
    labelsValues.push({ x: x, y: y + grid + margin, label: 'Message count', value: messageCount.toLocaleString() });
    labelsValues.push({ x: x, y: y + 2 * (grid + margin), label: 'Message rate/s', value: messageRatePerSecond.toLocaleString() });
  }

  const nodes = gStatistics.selectAll('g')
    .data(labelsValues);
 
  const nodesEnter = nodes.enter().append('g')
    .attr('cursor', 'pointer')
    .on('click', clickMember);

  nodesEnter.append('rect')
    .attr('x', d => d.x)
    .attr('y', d => d.y)
    .attr('width', widthLabel)
    .attr('height', grid)
    .style('fill', bgColor);

  nodesEnter.append('text')
    .attr('x', d => d.x + margin)
    .attr('y', d => d.y + grid - margin)
    .attr('text-anchor', 'start')
    .style('font-size', grid - margin * 2.5)
    .style('fill', txColor)
    .text(d => d.label);

  nodesEnter.append('rect')
    .attr('x', d => d.x + widthLabel + margin)
    .attr('y', d => d.y)
    .attr('width', widthValue)
    .attr('height', grid)
    .style('fill', bgColor);

  nodesEnter.append('text')
    .attr('x', d => d.x + widthLabel + widthValue - margin)
    .attr('y', d => d.y + grid - margin)
    .attr('text-anchor', 'end')
    .attr('class', 'statistics')
    .style('font-size', grid - margin * 2.5)
    .style('fill', txColor)
    .text(d => d.value);

  nodes.select('text.statistics')
    .text(d => d.value);

  nodes.exit()
    .remove();
}

function showServerLinks(ip) {
  return hiddenMemberLinkViews.find(s => s.includes(ip)) ? false : true;
}

function linkId(d) {
  return d.source.data.name + '-' + d.target.data.name;
}

function nodeId(d) {
  return d.data.type + '-' + d.data.name;
}

function entityColor(d) {
  return d.data.name == traceEntityId ? '#FF0000' : '#42aaff';
}

function shardColor(d) {
  return d.data.name == traceShardId ? '#FF0000' : '#00C000';
}

function circleColor(d) {
  if (d.data.type.includes('entity')) {
    return d.data.name == traceEntityId ? '#AA0000' : '#046E97';
  } else if (d.data.type.includes('shard')) {
    return d.data.name == traceShardId ? '#AA0000' : '#00C000';
  } else if (d.data.type.includes('singleton')) {
    return '#8F42EB';
  } else if (d.data.type.includes('httpServer')) {
    return '#F3B500';
  } else if (d.data.type.includes('member')) {
    return '#F17D00';
  } else if (d.data.type.includes('cluster')) {
    return '#B30000';
  } else {
    return 'red';
  }
}

function circleRadius(d) {
  if (d.data.type.includes('entity')) {
    return 8;
  } else if (d.data.type.includes('shard')) {
    return 12;
  } else if (d.data.type.includes('member')) {
    return 22;
  } else if (d.data.type.includes('cluster')) {
    return 10;
  } else {
    return 3;
  }
}

function circleRadiusExit(d) {
  return 4 * circleRadius(d);
}

function labelOffsetX(d) {
  if (d.data.type.includes('entity')) {
    return offset(d, 10);
  } else if (d.data.type.includes('shard')) {
    return offset(d, 14);
  } else if (d.data.type.includes('member')) {
    return offset(d, 24);
  } else if (d.data.type.includes('cluster')) {
    return offset(d, 12);
  } else {
    return offset(d, 5);
  }

  function offset(d, distance) {
    return d.x < Math.PI === !d.children ? distance : -distance;
  }
}

function memberNumber(d) {
  // expect: 'akka://cluster@172.17.0.3:25520'
  const addr1 = d.data.name.split(':');
  const addr2 = addr1.length == 3 ? addr1[1].split('.') : ['X'];
  return addr2.length == 4 ? addr2[3] : 'X';
}

function clickCircle(d) {
  if (d.data.type.indexOf('member') >= 0) {
    toggleMemberLinkView(d);
  } else if (d.data.type == 'entity') {
    traceEntityId = d.data.name == traceEntityId ? '' : d.data.name;
    traceShardId = traceEntityId.length > 0 ? d.parent.data.name : '';
  } else if (d.data.type == 'shard') {
    traceEntityId = '';
    traceShardId = d.data.name == traceShardId ? '' : d.data.name;
  }
}

function clickMember(d) {
  const member = `akka://cluster@${d.ip}:25520`;
  sendWebSocketRequest(member);
}

const hiddenMemberLinkViews = [];

function toggleMemberLinkView(d) {
  const i = hiddenMemberLinkViews.indexOf(d.data.name);
  if (i >= 0) {
    hiddenMemberLinkViews.splice(i, 1);
  } else {
    hiddenMemberLinkViews.push(d.data.name);
  }
}

let traceEntityIdNew = '';
let traceEntityId = '';
let traceShardId = '';

d3.select('body').on('keydown', function () {
  if ((d3.event.key >= '0' && d3.event.key <= '9') || d3.event.key == '-') {
    traceEntityIdNew += d3.event.key;
  } else if (d3.event.key == 'Enter') {
    traceEntityId = traceEntityIdNew;
    traceEntityIdNew = '';
  }
});
