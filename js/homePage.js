document.addEventListener("DOMContentLoaded", () => {
    // ===== SVG Ecosystem Diagram =====
    const svg = document.getElementById('hex-cycle-svg');
    if (!svg) {
        return;
    }
    const nodesGroup = svg.getElementById('nodes');
    const flowsGroup = svg.getElementById('flows');
    if (!nodesGroup || !flowsGroup) {
        return;
    }

    const svgNS = 'http://www.w3.org/2000/svg';
    const xlinkNS = 'http://www.w3.org/1999/xlink';
    const cx = 350;
    const cy = 350;
    const arrowRadius = 170;
    const nodeCenterRadius = 220;
    const hexOuter = 50;
    const nodeImages = [
        'images/tuan_hoan/Ong.jpg',
        'images/tuan_hoan/HoaTrang.jpg',
        'images/tuan_hoan/TrunQue.jpg',
        'images/tuan_hoan/CaTre.jpg',
        'images/tuan_hoan/RanRiVoi.webp',
        'images/tuan_hoan/RauCai.jpg'
    ];
    const nodeLabels = ['Ong', 'Hoa Trang', 'Trùn Quế', 'Cá Trê', 'Rắn Ri Voi', 'Rau Cải'];
    const nodeInfo = [
        "Tăng năng suất nhờ thụ phấn tự nhiên",
        "Nguồn dược liệu bản địa, mật hoa nuôi ong",
        "Xử lý phế phẩm, tạo phân hữu cơ",
        "Nuôi bằng phụ phẩm, cung cấp thức ăn cho rắn",
        "Được nuôi bằng cá trê, giá trị dược liệu",
        "Sử dụng nước thải đã xử lý từ hệ sinh thái"
    ];
    const angles = [-90, -30, 30, 90, 150, 210];
    const toRad = d => d * Math.PI / 180;

    const nodeCenters = angles.map(a => {
        const rad = toRad(a);
        return {
            cx: cx + nodeCenterRadius * Math.cos(rad),
            cy: cy + nodeCenterRadius * Math.sin(rad),
            angle: a
        };
    });

    function hexPoints(x, y, s) {
        const pts = [];
        for (let i = 0; i < 6; i++) {
            const angDeg = -90 + 60 * i;
            const rad = toRad(angDeg);
            pts.push(`${x + s * Math.cos(rad)},${y + s * Math.sin(rad)}`);
        }
        return pts.join(' ');
    }

    // Create tooltip using foreignObject
    const fo = document.createElementNS(svgNS, 'foreignObject');
    fo.setAttribute('id', 'tooltip-fo');
    fo.setAttribute('x', cx - 80);
    fo.setAttribute('y', cy + 40);
    fo.setAttribute('width', 160);
    fo.setAttribute('height', 80);
    const div = document.createElement('div');
    div.setAttribute('xmlns', 'http://www.w3.org/1999/xhtml');
    div.style.display = 'none';
    fo.appendChild(div);
    svg.appendChild(fo);

    const defs = svg.querySelector('defs') || (() => {
        const d = document.createElementNS(svgNS, 'defs');
        svg.insertBefore(d, svg.firstChild);
        return d;
    })();

    // Draw nodes
    nodeCenters.forEach((n, idx) => {
        const g = document.createElementNS(svgNS, 'g');
        g.setAttribute('class', 'hex-group');

        // Clip path for hex image
        const clip = document.createElementNS(svgNS, 'clipPath');
        clip.setAttribute('id', `hex-clip-${idx}`);
        const clipPoly = document.createElementNS(svgNS, 'polygon');
        clipPoly.setAttribute('points', hexPoints(n.cx, n.cy, hexOuter));
        clip.appendChild(clipPoly);
        defs.appendChild(clip);

        // Image fill
        const img = document.createElementNS(svgNS, 'image');
        img.setAttribute('x', (n.cx - hexOuter).toString());
        img.setAttribute('y', (n.cy - hexOuter).toString());
        img.setAttribute('width', (hexOuter * 2).toString());
        img.setAttribute('height', (hexOuter * 2).toString());
        img.setAttribute('preserveAspectRatio', 'xMidYMid slice');
        img.setAttribute('clip-path', `url(#hex-clip-${idx})`);
        img.setAttributeNS(xlinkNS, 'href', nodeImages[idx]);
        img.setAttribute('href', nodeImages[idx]);
        g.appendChild(img);

        // Polygon border
        const poly = document.createElementNS(svgNS, 'polygon');
        poly.setAttribute('class', 'hex-polygon');
        poly.setAttribute('points', hexPoints(n.cx, n.cy, hexOuter));
        g.appendChild(poly);

        // Label text
        const label = document.createElementNS(svgNS, 'text');
        label.setAttribute('x', n.cx);
        label.setAttribute('y', n.cy + hexOuter + 20);
        label.setAttribute('text-anchor', 'middle');
        label.setAttribute('font-size', '13');
        label.setAttribute('fill', '#2d5016');
        label.setAttribute('font-weight', '600');
        label.setAttribute('class', 'hex-label');
        label.textContent = nodeLabels[idx];
        g.appendChild(label);

        // Hover events
        g.addEventListener('mouseenter', () => {
            div.textContent = nodeInfo[idx];
            div.style.display = 'block';
        });
        g.addEventListener('mouseleave', () => {
            div.style.display = 'none';
        });

        nodesGroup.appendChild(g);
    });

    // Draw arrows
    const offsetAngleRad = Math.asin(hexOuter / nodeCenterRadius);
    const offsetDeg = (offsetAngleRad * 180 / Math.PI) + 8;
    for (let i = 0; i < angles.length; i++) {
        let a1 = angles[i];
        let a2 = angles[(i + 1) % angles.length];
        let startDeg = a1 + offsetDeg;
        let endDeg = a2 - offsetDeg;
        while (startDeg < 0) startDeg += 360;
        while (endDeg < 0) endDeg += 360;
        if (endDeg <= startDeg) endDeg += 360;
        const sx = cx + arrowRadius * Math.cos(toRad(startDeg));
        const sy = cy + arrowRadius * Math.sin(toRad(startDeg));
        const ex = cx + arrowRadius * Math.cos(toRad(endDeg));
        const ey = cy + arrowRadius * Math.sin(toRad(endDeg));
        const path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
        path.setAttribute('class', 'flow');
        path.setAttribute('d', `M ${sx.toFixed(3)} ${sy.toFixed(3)} A ${arrowRadius} ${arrowRadius} 0 0 1 ${ex.toFixed(3)} ${ey.toFixed(3)}`);
        path.setAttribute('marker-end', 'url(#arrow)');
        flowsGroup.appendChild(path);
    }
});
