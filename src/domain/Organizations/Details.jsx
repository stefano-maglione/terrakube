import { React, useState, useEffect } from "react";
import { Button, Layout, Breadcrumb ,Input,List,Space,Card,Tag} from "antd";
import { GitlabOutlined,GithubOutlined, ClockCircleOutlined, CheckCircleOutlined } from '@ant-design/icons';
import { SiTerraform,SiBitbucket, SiAzuredevops  } from "react-icons/si";
import { MdBusiness } from 'react-icons/md';
import { IconContext } from "react-icons";
import axiosInstance from "../../config/axiosConfig";
import {useParams,useHistory,Link} from "react-router-dom";
import { ORGANIZATION_ARCHIVE,ORGANIZATION_NAME } from '../../config/actionTypes';
const { Content } = Layout;
const { DateTime } = require("luxon");
const { Search } = Input;
const include = {
  WORKSPACE: "workspace"
}


export const OrganizationDetails = ({setOrganizationName,organizationName}) => {
  const { id } = useParams();
  const [organization, setOrganization] = useState({});
  const [workspaces, setWorkspaces] = useState([]);
  const [loading, setLoading] = useState(false);
  const history = useHistory();
  const handleCreate = e => {
      history.push("/workspaces/create");
  };
  const renderVCSLogo = (vcs) => {
    switch (vcs) {
      case 'GITLAB':
        return <GitlabOutlined style={{ fontSize: '18px' }} />;
      case 'BITBUCKET':
        return <IconContext.Provider value={{ size: "18px" }}><SiBitbucket />&nbsp;</IconContext.Provider>;
      case 'AZURE_DEVOPS':
        return <IconContext.Provider value={{ size: "18px" }}><SiAzuredevops />&nbsp;</IconContext.Provider>;
      default:
        return <GithubOutlined style={{ fontSize: '18px' }} />;
    }
  }

  const handleClick = id => {
    console.log(id);
    history.push("/workspaces/"+id)
  };

  useEffect(() => {
    setLoading(true);
    localStorage.setItem(ORGANIZATION_ARCHIVE, id);
    axiosInstance.get(`organization/${id}?include=workspace`)
      .then(response => {
        console.log(response);
        setOrganization(response.data);
       
        if (response.data.included) {
          setupOrganizationIncludes(response.data.included, setWorkspaces);
        }

        setLoading(false);
        localStorage.setItem(ORGANIZATION_NAME,response.data.data.attributes.name)
        setOrganizationName(response.data.data.attributes.name)
      });
      
  }, [id]);

  return (
    <Content style={{ padding: '0 50px' }}>
      <Breadcrumb style={{ margin: '16px 0' }}>
        <Breadcrumb.Item>{organizationName}</Breadcrumb.Item>
        <Breadcrumb.Item>Workspaces</Breadcrumb.Item>
      </Breadcrumb>
      <div className="site-layout-content">
        {loading || !organization.data || !workspaces ? (
          <p>Data loading...</p>
        ) : (
          <div className="workspaceWrapper">
            <div className='variableActions'><h2>Workspaces</h2><Button type="primary" htmlType="button" onClick={handleCreate}>New workspace</Button></div>
            <Search placeholder="Filter workspaces" style={{ width: "100%" }} />
            <List split="" className="workspaceList" dataSource={workspaces}
              renderItem={item => (
                <List.Item>
                  <Card onClick={() => handleClick(item.id)} style={{ width: "100%" }} hoverable>
                    <Space style={{ color: "rgb(82, 87, 97)" }} direction="vertical" >
                      <h3>{item.name}</h3>
                      {item.description}
                      <Space size={40} style={{ marginTop: "25px" }}>
                        <Tag color="#2eb039" icon={<CheckCircleOutlined />}>completed</Tag>
                        <span><ClockCircleOutlined />&nbsp;&nbsp;1 minute ago</span>
                        <span><IconContext.Provider value={{ size: "1.3em" }}><SiTerraform /></IconContext.Provider>&nbsp;&nbsp;{item.terraformVersion}</span>
                        <span><GithubOutlined style={{ fontSize: '18px' }} />&nbsp; <a href={item.source} target="_blank">{item.source.replace(".git", "").replace("https://github.com/", "")}</a></span>
                     
                      </Space>
                    </Space>
                  </Card>
                </List.Item>
              )}
            />
          </div>
        )}
      </div>
    </Content>

  );
}

function setupOrganizationIncludes(includes, setWorkspaces) {
  let workspaces = [];

  includes.forEach(element => {
    switch (element.type) {
      case include.WORKSPACE:
        workspaces.push(
          {
            id: element.id,
            latestChange: "1 minute ago" ,
            ...element.attributes
          }
        );
        break;
      default:
        break;
    }
  });

  setWorkspaces(workspaces);
}